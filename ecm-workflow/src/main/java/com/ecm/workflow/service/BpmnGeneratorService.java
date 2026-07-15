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

        xml.append("  </process>\n");

        // Generate BPMN diagram layout — use _flowLayout positions if available, else auto-layout
        xml.append(generateDiagramLayout(dsl.getProcessKey(), declared, flows, dsl.getFlowLayout()));

        xml.append("</definitions>\n");
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
            case DOCUSIGN -> generateDocuSignTask(step, xml, flows, declared);
        }
    }
    /**
     * Generates a DocuSign signing step with async wait:
     *   [Service Task: create envelope] → [Receive Task: wait for webhook] → next step
     *
     * The receive task (id: "docuSignWait") pauses the workflow until
     * DocuSignCompletedListener triggers it via runtimeService.trigger().
     */
    private void generateDocuSignTask(DslStep step, StringBuilder xml,
                                      List<String> flows, Set<String> declared) {
        String serviceTaskId = step.getId();
        // Must match DocuSignCompletedListener.RECEIVE_TASK_ACTIVITY_ID
        String waitTaskId = "docuSignWait";

        // 1. Service task — calls ecm-eforms to create DocuSign envelope
        xml.append(String.format("""
              <serviceTask id="%s" name="%s"
                  flowable:delegateExpression="${docuSignDelegate}">
                <extensionElements>
                  <flowable:field name="subjectTemplate"
                      stringValue="%s"/>
                  <flowable:field name="recipientEmailVar"
                      stringValue="%s"/>
                </extensionElements>
              </serviceTask>
            """,
                serviceTaskId,
                escape(step.getName()),
                escape(step.getDocusignSubjectTemplate() != null
                        ? step.getDocusignSubjectTemplate() : "Please sign your document"),
                escape(step.getDocusignRecipientEmailVar() != null
                        ? step.getDocusignRecipientEmailVar() : "submitterEmail")));
        declared.add(serviceTaskId);

        // 2. Receive task — waits for DocuSign webhook callback
        xml.append(String.format("""
              <receiveTask id="%s" name="Awaiting Signature" />
            """, waitTaskId));
        declared.add(waitTaskId);

        // Flow: service task → receive task
        flows.add(sequenceFlow("flow_" + serviceTaskId + "_to_wait",
                serviceTaskId, waitTaskId, null));

        // Flow: receive task → next step
        if (!step.getOutcomes().isEmpty()) {
            flows.add(sequenceFlow("flow_" + waitTaskId + "_next",
                    waitTaskId, step.getOutcomes().get(0).getNext(), null));
        }
    }

    private void generateUserTask(DslStep step, WorkflowTemplateDsl dsl,
                                  StringBuilder xml, List<String> flows,
                                  Set<String> declared) {
        String groupExpr = step.getCandidateGroupVariable() != null
                ? "${" + step.getCandidateGroupVariable() + "}"
                : "${candidateGroup}";

        xml.append(String.format("""
                  <userTask id="%s" name="%s"
                      flowable:candidateGroups="%s"
                      flowable:formFieldValidation="false">
                    <extensionElements>
                      <flowable:taskListener event="create"
                          delegateExpression="${taskCreatedListener}"/>
                      <flowable:taskListener event="complete"
                          delegateExpression="${taskCompletedListener}"/>
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
                      <flowable:taskListener event="create"
                          delegateExpression="${taskCreatedListener}"/>
                      <flowable:taskListener event="complete"
                          delegateExpression="${taskCompletedListener}"/>
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
                          <flowable:taskListener event="create"
                              delegateExpression="${taskCreatedListener}"/>
                          <flowable:taskListener event="complete"
                              delegateExpression="${taskCompletedListener}"/>
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
                      flowable:delegateExpression="${notificationDelegate}"/>
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
                             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"
                             xmlns:omgdc="http://www.omg.org/spec/DD/20100524/DC"
                             xmlns:omgdi="http://www.omg.org/spec/DD/20100524/DI"
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
                      <conditionExpression>%s</conditionExpression>
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

    /**
     * Generate a BPMN DI diagram section.
     *
     * Strategy:
     *   1. If _flowLayout has saved positions from Simple mode → use them (with size offset)
     *   2. Else → BFS auto-layout from start
     *
     * Flow node IDs map to BPMN element IDs:
     *   - start_1 → "start" (BPMN start event is always id="start")
     *   - reviewTask_X → step ID in BPMN (same)
     *   - decision_X → maps to "gw_{upstream_step_id}" in BPMN
     *   - end_approved → end state ID (same)
     */
    private String generateDiagramLayout(String processKey, Set<String> declaredIds,
                                          List<String> flows, WorkflowTemplateDsl.FlowLayout flowLayout) {

        // ── Build flow position lookup from _flowLayout ──────────────────────
        // Maps flow node ID → {x, y} from Simple mode canvas
        Map<String, int[]> flowPositions = new LinkedHashMap<>();
        if (flowLayout != null && flowLayout.getNodes() != null) {
            for (var fn : flowLayout.getNodes()) {
                if (fn.getId() == null || fn.getPosition() == null) continue;
                int fx = toInt(fn.getPosition().get("x"));
                int fy = toInt(fn.getPosition().get("y"));
                flowPositions.put(fn.getId(), new int[]{fx, fy});
            }
        }
        boolean hasFlowPositions = !flowPositions.isEmpty();
        log.debug("Flow layout: {} nodes with positions (hasPositions={})",
                flowPositions.size(), hasFlowPositions);

        // ── Build adjacency list from flows ──────────────────────────────────
        Map<String, List<String>> outgoing = new LinkedHashMap<>();
        for (String flow : flows) {
            String src = extractAttr(flow, "sourceRef");
            String tgt = extractAttr(flow, "targetRef");
            if (src != null && tgt != null) {
                outgoing.computeIfAbsent(src, k -> new ArrayList<>()).add(tgt);
            }
        }

        // Step 1: Assign depth via BFS from start
        Map<String, Integer> depthMap = new LinkedHashMap<>();
        Map<String, Integer> branchMap = new LinkedHashMap<>();   // id → branch offset
        Map<String, Integer> branchTotal = new LinkedHashMap<>(); // id → total siblings

        int X_START = 150;
        int Y_CENTER = 250;
        int X_STEP = 200;
        int Y_BRANCH = 120;

        // BFS to assign depths
        List<String[]> queue = new ArrayList<>();
        queue.add(new String[]{"start", "0"});
        Set<String> visited = new LinkedHashSet<>();

        while (!queue.isEmpty()) {
            String[] entry = queue.remove(0);
            String id = entry[0];
            int depth = Integer.parseInt(entry[1]);

            if (visited.contains(id)) continue;
            visited.add(id);
            depthMap.put(id, depth);

            List<String> children = outgoing.getOrDefault(id, List.of());
            for (int i = 0; i < children.size(); i++) {
                String child = children.get(i);
                if (!visited.contains(child)) {
                    branchMap.put(child, i);
                    branchTotal.put(child, children.size());
                    queue.add(new String[]{child, String.valueOf(depth + 1)});
                }
            }
        }

        // Step 2: Calculate positions — prefer _flowLayout, fall back to auto-layout
        Map<String, int[]> positions = new LinkedHashMap<>();

        if (hasFlowPositions) {
            // ── Use Simple mode positions with size offset ────────────────────
            // Map flow node IDs → BPMN element IDs
            // Flow "start_1" → BPMN "start"
            // Flow "decision_X" → BPMN "gw_Y" (the gateway created for upstream step)
            // Flow step IDs → same in BPMN
            // Flow end IDs → same in BPMN

            // Size offsets: center smaller BPMN shape within larger React Flow node
            // React Flow cards ~170x60, BPMN tasks 120x80
            // React Flow circles ~56, BPMN events 36
            Map<String, int[]> sizeOffset = Map.of(
                    "start", new int[]{10, 10, 36, 36},
                    "event", new int[]{6, 14, 36, 36},
                    "gateway", new int[]{3, 3, 50, 50},
                    "task", new int[]{25, -10, 120, 80}
            );

            for (String bpmnId : declaredIds) {
                boolean isEvent = bpmnId.startsWith("start") || bpmnId.startsWith("end");
                boolean isGateway = bpmnId.startsWith("gw_") || bpmnId.startsWith("fork_") || bpmnId.startsWith("join_");

                // Find matching flow node position
                int[] flowPos = null;

                if ("start".equals(bpmnId)) {
                    // Try start_1, start_2, etc.
                    for (String fk : flowPositions.keySet()) {
                        if (fk.startsWith("start")) { flowPos = flowPositions.get(fk); break; }
                    }
                } else if (isGateway) {
                    // gw_review_1 → look for decision node connected to review_1
                    // The gateway ID is "gw_{stepId}" — the decision in flow is "decision_{stepId}" or similar
                    String stepId = bpmnId.substring(3); // remove "gw_"
                    // Try "decision_" + stepId, or any decision node
                    for (String fk : flowPositions.keySet()) {
                        if (fk.startsWith("decision") && (fk.contains(stepId) || fk.endsWith(stepId))) {
                            flowPos = flowPositions.get(fk);
                            break;
                        }
                    }
                    if (flowPos == null) {
                        // Try any decision node as fallback
                        for (String fk : flowPositions.keySet()) {
                            if (fk.startsWith("decision")) { flowPos = flowPositions.get(fk); break; }
                        }
                    }
                } else {
                    // Direct ID match (step IDs, end state IDs)
                    flowPos = flowPositions.get(bpmnId);
                }

                int[] offset = isEvent && bpmnId.startsWith("start") ? sizeOffset.get("start")
                        : isEvent ? sizeOffset.get("event")
                        : isGateway ? sizeOffset.get("gateway")
                        : sizeOffset.get("task");

                int w = offset[2];
                int h = offset[3];

                if (flowPos != null) {
                    int x = flowPos[0] + offset[0];
                    int y = flowPos[1] + offset[1];
                    positions.put(bpmnId, new int[]{x, y, w, h});
                    log.debug("Layout (flow): {} → x={}, y={} (from flow pos {}, {})", bpmnId, x, y, flowPos[0], flowPos[1]);
                } else {
                    // No flow position — use BFS depth fallback
                    int depth = depthMap.getOrDefault(bpmnId, visited.size());
                    int x = X_START + depth * X_STEP;
                    positions.put(bpmnId, new int[]{x, Y_CENTER - h / 2, w, h});
                    log.debug("Layout (auto): {} → x={}, y={} (depth={})", bpmnId, x, Y_CENTER - h / 2, depth);
                }
            }
        } else {
            // ── Auto-layout: group by depth, spread vertically ───────────────
            Map<Integer, List<String>> depthGroups = new LinkedHashMap<>();
            for (String id : visited) {
                depthGroups.computeIfAbsent(depthMap.getOrDefault(id, 0), k -> new ArrayList<>()).add(id);
            }

            for (Map.Entry<Integer, List<String>> entry : depthGroups.entrySet()) {
                int depth = entry.getKey();
                List<String> nodesAtDepth = entry.getValue();
                int count = nodesAtDepth.size();

                for (int i = 0; i < count; i++) {
                    String id = nodesAtDepth.get(i);
                    boolean isEvent = id.startsWith("start") || id.startsWith("end");
                    boolean isGateway = id.startsWith("gw_") || id.startsWith("fork_") || id.startsWith("join_");
                    int w = isEvent ? 36 : isGateway ? 50 : 120;
                    int h = isEvent ? 36 : isGateway ? 50 : 80;

                    int x = X_START + depth * X_STEP;
                    int yOffset = count > 1 ? (2 * i - (count - 1)) * Y_BRANCH / 2 : 0;
                    int y = Y_CENTER + yOffset - h / 2;

                    positions.put(id, new int[]{x, y, w, h});
                    log.debug("Layout (auto): {} → depth={}, x={}, y={}, slot={}/{}", id, depth, x, y, i, count);
                }
            }

            // Remaining declared elements not reached by flow walk
            int extraX = X_START + (visited.size() + 1) * X_STEP;
            for (String id : declaredIds) {
                if (!positions.containsKey(id)) {
                    boolean isEvent = id.startsWith("start") || id.startsWith("end");
                    boolean isGateway = id.startsWith("gw_") || id.startsWith("fork_") || id.startsWith("join_");
                    int w = isEvent ? 36 : isGateway ? 50 : 120;
                    int h = isEvent ? 36 : isGateway ? 50 : 80;
                    positions.put(id, new int[]{extraX, Y_CENTER - h / 2, w, h});
                    extraX += w + 80;
                }
            }
        }

        // Build diagram XML
        StringBuilder di = new StringBuilder();
        di.append("  <bpmndi:BPMNDiagram id=\"BPMNDiagram_1\">\n");
        di.append("    <bpmndi:BPMNPlane id=\"BPMNPlane_1\" bpmnElement=\"").append(processKey).append("\">\n");

        for (Map.Entry<String, int[]> e : positions.entrySet()) {
            String id = e.getKey();
            int[] pos = e.getValue();
            boolean isGateway = id.startsWith("gw_") || id.startsWith("fork_") || id.startsWith("join_");
            di.append(String.format(
                    "      <bpmndi:BPMNShape id=\"%s_di\" bpmnElement=\"%s\"%s>\n" +
                    "        <omgdc:Bounds x=\"%d\" y=\"%d\" width=\"%d\" height=\"%d\"/>\n" +
                    "      </bpmndi:BPMNShape>\n",
                    id, id, isGateway ? " isMarkerVisible=\"true\"" : "",
                    pos[0], pos[1], pos[2], pos[3]));
        }

        for (String flow : flows) {
            String flowId = extractAttr(flow, "id");
            String src = extractAttr(flow, "sourceRef");
            String tgt = extractAttr(flow, "targetRef");
            if (flowId == null || src == null || tgt == null) continue;

            int[] srcPos = positions.get(src);
            int[] tgtPos = positions.get(tgt);
            if (srcPos == null || tgtPos == null) continue;

            int srcCx = srcPos[0] + srcPos[2];
            int srcCy = srcPos[1] + srcPos[3] / 2;
            int tgtCx = tgtPos[0];
            int tgtCy = tgtPos[1] + tgtPos[3] / 2;

            di.append(String.format(
                    "      <bpmndi:BPMNEdge id=\"%s_di\" bpmnElement=\"%s\">\n" +
                    "        <omgdi:waypoint x=\"%d\" y=\"%d\"/>\n" +
                    "        <omgdi:waypoint x=\"%d\" y=\"%d\"/>\n" +
                    "      </bpmndi:BPMNEdge>\n",
                    flowId, flowId, srcCx, srcCy, tgtCx, tgtCy));
        }

        di.append("    </bpmndi:BPMNPlane>\n");
        di.append("  </bpmndi:BPMNDiagram>\n");
        return di.toString();
    }

    /** Extract an XML attribute value from a fragment string. */
    private String extractAttr(String xml, String attr) {
        String search = attr + "=\"";
        int start = xml.indexOf(search);
        if (start < 0) return null;
        start += search.length();
        int end = xml.indexOf('"', start);
        return end > start ? xml.substring(start, end) : null;
    }

    private static int toInt(Object o) {
        if (o == null) return 0;
        if (o instanceof Number n) return n.intValue();
        try { return (int) Double.parseDouble(o.toString()); } catch (NumberFormatException e) { return 0; }
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}