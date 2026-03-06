package com.ecm.eforms.model.schema;

import lombok.Data;
import java.util.Map;

/**
 * Workflow trigger configuration stored as JSONB in form_definitions.workflow_config.
 * When a form is submitted, FormSubmissionService reads this config and publishes
 * a FormSubmittedEvent to RabbitMQ. ecm-workflow consumes that event and creates
 * a WorkflowInstance using workflowDefinitionKey.
 */
@Data
public class WorkflowConfig {
    private boolean            triggerOnSubmit     = true;
    private String             workflowDefinitionKey; // key in ecm-workflow
    private String             assignToRole;          // default assignee role
    private String             defaultPriority    = "NORMAL"; // LOW | NORMAL | HIGH | URGENT
    private Integer            slaDays            = 5;
    /** Maps submission_data field keys to workflow variables */
    private Map<String, String> fieldMappings;
}
