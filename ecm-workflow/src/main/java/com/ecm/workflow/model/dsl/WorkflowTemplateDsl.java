package com.ecm.workflow.model.dsl;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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

    /**
     * React Flow canvas layout — preserved through round-trip for position persistence.
     * Not used by BpmnGeneratorService (ignored during BPMN generation).
     * Contains node positions, edge routing, and visual state.
     */
    @JsonProperty("_flowLayout")
    private FlowLayout flowLayout;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlowLayout {
        private List<FlowNode> nodes;
        private List<FlowEdge> edges;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlowNode {
        private String id;
        private String type;
        private Map<String, Object> position;  // { x, y }
        private Map<String, Object> data;      // { label, assignedGroup, ... }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FlowEdge {
        private String id;
        private String source;
        private String target;
        private String sourceHandle;
        private String targetHandle;
        private String label;
        private List<Map<String, Object>> waypoints;  // For custom edge routing
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DslStep {
        private String id;
        private DslStepType type;
        private String name;

        /** Variable key from 'variables' map whose value is the Flowable candidate group */
        private String candidateGroupVariable;
        // Fields read by BpmnGeneratorService when type == DOCUSIGN
        private String docusignSubjectTemplate;   // e.g. "Please sign: {documentName}"
        private String docusignRecipientEmailVar; // process variable name holding email
        private String docusignRecipientNameVar;  // process variable name holding name

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

        /** Handles DSL outcomes as plain strings: "APPROVED" → DslOutcome(id=APPROVED) */
        @JsonCreator
        public static DslOutcome fromString(String value) {
            DslOutcome o = new DslOutcome();
            o.setId(value);
            o.setLabel(value);
            return o;
        }

        public DslOutcome() {}
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
        NOTIFICATION,
        DOCUSIGN
    }
}