package com.ecm.workflow.service;

import com.ecm.workflow.model.dsl.WorkflowTemplateDsl;
import com.ecm.workflow.model.dsl.WorkflowTemplateDsl.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Generates valid Flowable-compatible BPMN 2.0 XML from a WorkflowTemplateDsl.
 *
 * Strategy: Walk the DSL steps, build up XML fragments, stitch into a
 * complete <definitions> document.
 *
 * Generated elements per step type:
 *  USER_TASK      → <userTask> + <exclusiveGateway> (if multiple outcomes)
 *  PARALLEL_TASKS → <parallelGateway> fork + N <userTask> + <parallelGateway> join
 *  INFO_WAIT      → <userTask> assigned to submitter (${initiator})
 *  NOTIFICATION   → <serviceTask flowable:class="...NotificationDelegate">
 */
@Slf4j
@Service
public class BpmnGeneratorService {

    private static final String BPMN_NS  = "http://www.omg.org/spec/BPMN/20100524/MODEL";
    private static final String FLOWABLE = "http://flowable.org/bpmn";
    private static final String NS       = "http://www.flowable.org/processdef";

    public String generate(WorkflowTemplateDsl dsl) {
        log.info("Generating BPMN for process key: {}", dsl.getProcessKey());

        StringBuilder xml = new StringBuilder();
        List<String> flows = new ArrayList<>();   // <sequenceFlow> elements collected
        Set<String> declared = new LinkedHashSet<>(); // element ids in declaration order

        xml.append(xmlHeader(dsl.getProcessKey(), dsl.getName()));

        // Start event
        xml.append(startEvent("start", "Start"));
        declared.add("start");

        // First element after start
        String firstTarget = dsl.getSteps().isEmpty()
                ? "end_completed"
                : dsl.getSteps().get(0).getId();
        flows.add(sequenceFlow("flow_start", "start", firstTarget, null));

        // Walk steps
        for (int i = 0; i < dsl.getSteps().size(); i++) {
            DslStep step = dsl.getSteps().get(i);
            generateStep(step, dsl, xml, flows, declared);
        }

        // End states
        for (DslEndState end : dsl.getEndStates()) {
            xml.append(endEvent(end.getId(), end.getName(), end.getStatus()));
            declared.add(end.getId());
        }

        // Fallback end event if none defined
        if (dsl.getEndStates().isEmpty()) {
            xml.append(endEvent("end_completed", "Completed", "COMPLETED"));
        }

        // Append all sequence flows
        for (String flow : flows) {
            xml.append(flow);
        }

        xml.append("  </process>\n</definitions>\n");
        return xml.toString();
    }

    // ─── Step generators ─────────────────────────────────────────────────────

    private void generateStep(DslStep step, WorkflowTemplateDsl dsl,
                              StringBuilder xml, List<String> flows,
                              Set<String> declared) {
        switch (step.getType()) {
            case USER_TASK  -> generateUserTask(step, dsl, xml, flows, declared);
            case INFO_WAIT  -> generateInfoWait(step, xml, flows, declared);
            case PARALLEL_TASKS -> generateParallelTasks(step, dsl, xml, flows, declared);
            case NOTIFICATION   -> generateNotification(step, xml, flows, declared);
        }
    }

    private void generateUserTask(DslStep step, WorkflowTemplateDsl dsl,
                                  StringBuilder xml, List<String> flows,
                                  Set<String> declared) {
        String groupExpr = step.getCandidateGroupVariable() != null
                ? "${" + step.getCandidateGroupVariable() + "}"
                : "ECM_REVIEWER";

        xml.append(String.format("""
                  <userTask id="%s" name="%s"
                      flowable:candidateGroups="%s"
                      flowable:formFieldValidation="false">
                    <extensionElements>
                      <flowable:taskListener event="complete"
                          class="com.ecm.workflow.flowable.TaskCompletionListener"/>
                    </extensionElements>
                  </userTask>
                """, step.getId(), escape(step.getName()), groupExpr));
        declared.add(step.getId());

        if (step.getOutcomes().size() == 1) {
            // No gateway needed — direct flow
            DslOutcome outcome = step.getOutcomes().get(0);
            flows.add(sequenceFlow("flow_" + step.getId() + "_" + outcome.getId(),
                    step.getId(), outcome.getNext(), null));
        } else if (step.getOutcomes().size() > 1) {
            // Exclusive gateway
            String gwId = "gw_" + step.getId();
            xml.append(exclusiveGateway(gwId));
            declared.add(gwId);
            flows.add(sequenceFlow("flow_" + step.getId() + "_gw", step.getId(), gwId, null));

            for (DslOutcome outcome : step.getOutcomes()) {
                String condition = decisionCondition(outcome.getId());
                flows.add(sequenceFlow(
                        "flow_" + gwId + "_" + outcome.getId(),
                        gwId, outcome.getNext(), condition));
            }
        }
    }

    private void generateInfoWait(DslStep step, StringBuilder xml,
                                  List<String> flows, Set<String> declared) {
        // Assigned to the original submitter via process variable 'initiator'
        xml.append(String.format("""
                  <userTask id="%s" name="%s"
                      flowable:assignee="${initiator}"
                      flowable:formFieldValidation="false">
                    <extensionElements>
                      <flowable:taskListener event="complete"
                          class="com.ecm.workflow.flowable.TaskCompletionListener"/>
                    </extensionElements>
                  </userTask>
                """, step.getId(), escape(step.getName())));
        declared.add(step.getId());

        if (!step.getOutcomes().isEmpty()) {
            DslOutcome outcome = step.getOutcomes().get(0); // INFO_WAIT has one outcome
            flows.add(sequenceFlow("flow_" + step.getId() + "_" + outcome.getId(),
                    step.getId(), outcome.getNext(), null));
        }
    }

    private void generateParallelTasks(DslStep step, WorkflowTemplateDsl dsl,
                                       StringBuilder xml, List<String> flows,
                                       Set<String> declared) {
        String forkId = "fork_" + step.getId();
        String joinId = "join_" + step.getId();

        xml.append(parallelGateway(forkId, "Fork"));
        declared.add(forkId);

        List<String> taskIds = new ArrayList<>();
        for (DslStep sub : step.getParallelTasks()) {
            String groupExpr = sub.getCandidateGroupVariable() != null
                    ? "${" + sub.getCandidateGroupVariable() + "}"
                    : "ECM_REVIEWER";
            xml.append(String.format("""
                      <userTask id="%s" name="%s"
                          flowable:candidateGroups="%s"
                          flowable:formFieldValidation="false">
                        <extensionElements>
                          <flowable:taskListener event="complete"
                              class="com.ecm.workflow.flowable.TaskCompletionListener"/>
                        </extensionElements>
                      </userTask>
                    """, sub.getId(), escape(sub.getName()), groupExpr));
            declared.add(sub.getId());
            taskIds.add(sub.getId());
            flows.add(sequenceFlow("flow_fork_" + sub.getId(), forkId, sub.getId(), null));
            flows.add(sequenceFlow("flow_" + sub.getId() + "_join", sub.getId(), joinId, null));
        }

        xml.append(parallelGateway(joinId, "Join"));
        declared.add(joinId);

        // After join, route based on step outcomes
        if (!step.getOutcomes().isEmpty()) {
            if (step.getOutcomes().size() == 1) {
                flows.add(sequenceFlow("flow_join_next", joinId,
                        step.getOutcomes().get(0).getNext(), null));
            } else {
                String gwId = "gw_" + step.getId();
                xml.append(exclusiveGateway(gwId));
                declared.add(gwId);
                flows.add(sequenceFlow("flow_join_gw", joinId, gwId, null));
                for (DslOutcome outcome : step.getOutcomes()) {
                    flows.add(sequenceFlow("flow_" + gwId + "_" + outcome.getId(),
                            gwId, outcome.getNext(), decisionCondition(outcome.getId())));
                }
            }
        }
    }

    private void generateNotification(DslStep step, StringBuilder xml,
                                      List<String> flows, Set<String> declared) {
        xml.append(String.format("""
                  <serviceTask id="%s" name="%s"
                      flowable:class="com.ecm.workflow.flowable.NotificationDelegate"/>
                """, step.getId(), escape(step.getName())));
        declared.add(step.getId());

        if (!step.getOutcomes().isEmpty()) {
            flows.add(sequenceFlow("flow_" + step.getId() + "_next",
                    step.getId(), step.getOutcomes().get(0).getNext(), null));
        }
    }

    // ─── XML fragment builders ────────────────────────────────────────────────

    private String xmlHeader(String processKey, String name) {
        return String.format("""
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="%s"
                             xmlns:flowable="%s"
                             targetNamespace="%s">
                  <process id="%s" name="%s" isExecutable="true">
                """, BPMN_NS, FLOWABLE, NS, processKey, escape(name));
    }

    private String startEvent(String id, String name) {
        return String.format("    <startEvent id=\"%s\" name=\"%s\" " +
                "flowable:initiator=\"initiator\"/>\n", id, escape(name));
    }

    private String endEvent(String id, String name, String status) {
        return String.format("""
                    <endEvent id="%s" name="%s">
                      <extensionElements>
                        <flowable:executionListener event="end"
                            class="com.ecm.workflow.flowable.ProcessEndListener">
                          <flowable:field name="completionStatus" stringValue="%s"/>
                        </flowable:executionListener>
                      </extensionElements>
                    </endEvent>
                """, id, escape(name), status);
    }

    private String exclusiveGateway(String id) {
        return String.format("    <exclusiveGateway id=\"%s\"/>\n", id);
    }

    private String parallelGateway(String id, String name) {
        return String.format("    <parallelGateway id=\"%s\" name=\"%s\"/>\n",
                id, escape(name));
    }

    private String sequenceFlow(String id, String source, String target, String condition) {
        if (condition == null) {
            return String.format("    <sequenceFlow id=\"%s\" sourceRef=\"%s\" targetRef=\"%s\"/>\n",
                    id, source, target);
        }
        return String.format("""
                    <sequenceFlow id="%s" sourceRef="%s" targetRef="%s">
                      <conditionExpression xsi:type="tFormalExpression">%s</conditionExpression>
                    </sequenceFlow>
                """, id, source, target, condition);
    }

    private String decisionCondition(String outcomeId) {
        return switch (outcomeId) {
            case "approve"       -> "<![CDATA[${decision == 'APPROVE'}]]>";
            case "reject"        -> "<![CDATA[${decision == 'REJECT'}]]>";
            case "request_info"  -> "<![CDATA[${decision == 'REQUEST_INFO'}]]>";
            case "info_provided" -> "<![CDATA[${decision == 'INFO_PROVIDED'}]]>";
            case "escalate"      -> "<![CDATA[${decision == 'ESCALATE'}]]>";
            default              -> "<![CDATA[${decision == '" + outcomeId.toUpperCase() + "'}]]>";
        };
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}