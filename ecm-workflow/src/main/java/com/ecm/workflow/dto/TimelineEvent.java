package com.ecm.workflow.dto;

import java.time.OffsetDateTime;

/**
 * A single event in the unified document/workflow timeline.
 * Aggregated from form_submissions, workflow_instance_records,
 * workflow_task_history, and documents tables.
 */
public record TimelineEvent(
        String eventType,          // FORM_SUBMITTED, WORKFLOW_STARTED, TASK_CLAIMED, TASK_APPROVED, etc.
        String description,        // Human-readable description
        String actor,              // Who performed the action (email or system)
        String comment,            // Optional comment/notes
        String status,             // Status at this point (SUBMITTED, ACTIVE, APPROVED, etc.)
        OffsetDateTime timestamp
) {}
