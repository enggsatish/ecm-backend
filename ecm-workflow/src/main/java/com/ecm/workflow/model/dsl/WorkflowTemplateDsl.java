package com.ecm.workflow.model.dsl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * JSON DSL describing the logical structure of a workflow.
 * Stored as JSONB in workflow_templates.dsl_definition.
 * BpmnGeneratorService walks this to produce BPMN 2.0 XML.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowTemplateDsl {

    /** Unique key — becomes the Flowable processDefinitionKey */
    private String processKey;
    private String name;

    /** Default variable bindings injected at process start (e.g. reviewerGroup → ECM_REVIEWER) */
    private Map<String, String> variables = Map.of();

    private List<DslStep> steps = List.of();
    private List<DslEndState> endStates = List.of();

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DslStep {
        private String id;
        private DslStepType type;
        private String name;

        /** Variable key from 'variables' map whose value is the Flowable candidate group */
        private String candidateGroupVariable;

        /** For PARALLEL_TASKS: list of sub-tasks */
        private List<DslStep> parallelTasks = List.of();

        private List<DslOutcome> outcomes = List.of();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DslOutcome {
        private String id;
        private String label;
        private String next;   // references a step.id or endState.id
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DslEndState {
        private String id;
        private String name;
        /**
         * Maps to WorkflowInstanceRecord.Status on process completion.
         * Values: COMPLETED | REJECTED | CANCELLED
         */
        private String status;
    }

    public enum DslStepType {
        USER_TASK,
        PARALLEL_TASKS,
        INFO_WAIT,
        NOTIFICATION
    }
}