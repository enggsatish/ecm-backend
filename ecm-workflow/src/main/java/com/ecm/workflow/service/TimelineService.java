package com.ecm.workflow.service;

import com.ecm.workflow.dto.TimelineEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Builds a unified timeline for a document or form submission.
 *
 * Aggregates events from:
 *   - ecm_forms.form_submissions (submitted, approved, rejected)
 *   - ecm_workflow.workflow_instance_records (workflow started, completed)
 *   - ecm_workflow.workflow_task_history (claimed, approved, rejected, info requested)
 *   - ecm_core.documents (created, OCR completed)
 *
 * All queries use JdbcTemplate for cross-schema reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineService {

    private final JdbcTemplate jdbc;

    /**
     * Build timeline for a document by its ID.
     * Traces back through workflow instance → form submission.
     */
    @Transactional(readOnly = true)
    public List<TimelineEvent> getTimelineForDocument(UUID documentId) {
        List<TimelineEvent> events = new ArrayList<>();

        // 1. Document events
        addDocumentEvents(events, documentId);

        // 2. Find workflow instance for this document
        String processInstanceId = findProcessInstanceForDocument(documentId);

        if (processInstanceId != null) {
            // 3. Workflow events
            addWorkflowEvents(events, processInstanceId);

            // 4. Task history events
            addTaskHistoryEvents(events, processInstanceId);

            // 5. Find form submission via workflow instance
            String submissionId = findSubmissionForProcess(processInstanceId);
            if (submissionId != null) {
                addFormSubmissionEvents(events, submissionId);
            }
        }

        // 6. Find case linked to this document and include case notes
        addCaseNotesForDocument(events, documentId);

        // Sort chronologically
        events.sort(Comparator.comparing(TimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    /**
     * Build timeline for a form submission by its ID.
     * Traces forward through workflow → document.
     */
    @Transactional(readOnly = true)
    public List<TimelineEvent> getTimelineForSubmission(UUID submissionId) {
        List<TimelineEvent> events = new ArrayList<>();

        // 1. Form submission events
        addFormSubmissionEvents(events, submissionId.toString());

        // 2. Find workflow instance for this submission
        String processInstanceId = findProcessInstanceForSubmission(submissionId.toString());

        if (processInstanceId != null) {
            // 3. Workflow events
            addWorkflowEvents(events, processInstanceId);

            // 4. Task history events
            addTaskHistoryEvents(events, processInstanceId);
        }

        // 5. Find document created from this submission
        addDocumentEventsForSubmission(events, submissionId.toString());

        events.sort(Comparator.comparing(TimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private void addDocumentEvents(List<TimelineEvent> events, UUID documentId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT name, status, ocr_completed, uploaded_by_email,
                       created_at, updated_at
                FROM ecm_core.documents WHERE id = ?
                """, documentId);

            for (Map<String, Object> row : rows) {
                events.add(new TimelineEvent(
                        "DOCUMENT_CREATED",
                        "Document created: " + row.get("name"),
                        str(row.get("uploaded_by_email")),
                        null,
                        str(row.get("status")),
                        ts(row.get("created_at"))
                ));

                if (Boolean.TRUE.equals(row.get("ocr_completed"))) {
                    events.add(new TimelineEvent(
                            "OCR_COMPLETED",
                            "OCR processing completed",
                            "system",
                            null,
                            "ACTIVE",
                            ts(row.get("updated_at"))
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch document events for {}: {}", documentId, e.getMessage());
        }
    }

    private void addDocumentEventsForSubmission(List<TimelineEvent> events, String submissionId) {
        try {
            // Find document created by FormDocumentCreationService (name pattern: formKey-submissionId.pdf)
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, name, status, ocr_completed, created_at, updated_at
                FROM ecm_core.documents
                WHERE name LIKE '%' || ? || '%'
                ORDER BY created_at DESC LIMIT 1
                """, submissionId.substring(0, 8));

            for (Map<String, Object> row : rows) {
                events.add(new TimelineEvent(
                        "DOCUMENT_CREATED",
                        "Document created: " + row.get("name"),
                        "system",
                        null,
                        str(row.get("status")),
                        ts(row.get("created_at"))
                ));

                if (Boolean.TRUE.equals(row.get("ocr_completed"))) {
                    events.add(new TimelineEvent(
                            "OCR_COMPLETED",
                            "OCR processing completed",
                            "system",
                            null,
                            "ACTIVE",
                            ts(row.get("updated_at"))
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch document events for submission {}: {}", submissionId, e.getMessage());
        }
    }

    private void addFormSubmissionEvents(List<TimelineEvent> events, String submissionId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT form_key, status, submitted_by_name, submitted_by,
                       submitted_at, reviewed_by, reviewed_at, review_notes,
                       docusign_envelope_id, docusign_status, docusign_completed_at
                FROM ecm_forms.form_submissions WHERE id = ?::uuid
                """, submissionId);

            for (Map<String, Object> row : rows) {
                events.add(new TimelineEvent(
                        "FORM_SUBMITTED",
                        "Form submitted: " + row.get("form_key"),
                        str(row.get("submitted_by_name")) != null
                                ? str(row.get("submitted_by_name"))
                                : str(row.get("submitted_by")),
                        null,
                        "SUBMITTED",
                        ts(row.get("submitted_at"))
                ));

                String status = str(row.get("status"));
                if ("APPROVED".equals(status) || "REJECTED".equals(status)) {
                    events.add(new TimelineEvent(
                            "FORM_" + status,
                            "Form " + status.toLowerCase(),
                            str(row.get("reviewed_by")),
                            str(row.get("review_notes")),
                            status,
                            ts(row.get("reviewed_at"))
                    ));
                }

                // DocuSign events
                String envelopeId = str(row.get("docusign_envelope_id"));
                String docuSignStatus = str(row.get("docusign_status"));
                if (envelopeId != null && !envelopeId.isBlank()) {
                    // Sent for signature event — approximate timestamp from reviewed_at or submitted_at
                    OffsetDateTime sentAt = ts(row.get("reviewed_at")) != null
                            ? ts(row.get("reviewed_at"))
                            : ts(row.get("submitted_at"));
                    events.add(new TimelineEvent(
                            "DOCUSIGN_SENT",
                            "Sent for signature via DocuSign",
                            "system",
                            "Envelope: " + envelopeId.substring(0, Math.min(8, envelopeId.length())) + "...",
                            "PENDING_SIGNATURE",
                            sentAt
                    ));

                    // Completion events
                    if ("completed".equalsIgnoreCase(docuSignStatus)) {
                        events.add(new TimelineEvent(
                                "DOCUSIGN_SIGNED",
                                "Document signed via DocuSign",
                                str(row.get("submitted_by")),
                                null,
                                "SIGNED",
                                ts(row.get("docusign_completed_at"))
                        ));
                    } else if ("declined".equalsIgnoreCase(docuSignStatus)) {
                        events.add(new TimelineEvent(
                                "DOCUSIGN_DECLINED",
                                "Signature declined",
                                str(row.get("submitted_by")),
                                null,
                                "SIGN_DECLINED",
                                ts(row.get("docusign_completed_at"))
                        ));
                    } else if ("voided".equalsIgnoreCase(docuSignStatus)) {
                        events.add(new TimelineEvent(
                                "DOCUSIGN_VOIDED",
                                "Signing envelope voided",
                                "system",
                                null,
                                "VOIDED",
                                ts(row.get("docusign_completed_at"))
                        ));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch form events for {}: {}", submissionId, e.getMessage());
        }
    }

    private void addWorkflowEvents(List<TimelineEvent> events, String processInstanceId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, started_by_subject, created_at, completed_at, final_comment
                FROM ecm_workflow.workflow_instance_records
                WHERE process_instance_id = ?
                """, processInstanceId);

            for (Map<String, Object> row : rows) {
                events.add(new TimelineEvent(
                        "WORKFLOW_STARTED",
                        "Review workflow started",
                        str(row.get("started_by_subject")),
                        null,
                        "ACTIVE",
                        ts(row.get("created_at"))
                ));

                if (row.get("completed_at") != null) {
                    String status = str(row.get("status"));
                    events.add(new TimelineEvent(
                            "WORKFLOW_COMPLETED",
                            "Workflow completed: " + status,
                            "system",
                            str(row.get("final_comment")),
                            status,
                            ts(row.get("completed_at"))
                    ));
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch workflow events for {}: {}", processInstanceId, e.getMessage());
        }
    }

    private void addTaskHistoryEvents(List<TimelineEvent> events, String processInstanceId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT action, actor_subject, actor_email, comment, created_at
                FROM ecm_workflow.workflow_task_history
                WHERE process_instance_id = ?
                ORDER BY created_at ASC
                """, processInstanceId);

            for (Map<String, Object> row : rows) {
                String action = str(row.get("action"));
                String actor = str(row.get("actor_email")) != null
                        ? str(row.get("actor_email"))
                        : str(row.get("actor_subject"));

                String description = switch (action) {
                    case "CLAIMED"        -> "Task claimed";
                    case "RELEASED"       -> "Task released back to queue";
                    case "APPROVED"       -> "Task approved";
                    case "REJECTED"       -> "Task rejected";
                    case "INFO_REQUESTED" -> "Additional information requested";
                    case "INFO_PROVIDED"  -> "Information provided";
                    default               -> "Task action: " + action;
                };

                events.add(new TimelineEvent(
                        "TASK_" + action,
                        description,
                        actor,
                        str(row.get("comment")),
                        action,
                        ts(row.get("created_at"))
                ));
            }
        } catch (Exception e) {
            log.debug("Could not fetch task history for {}: {}", processInstanceId, e.getMessage());
        }
    }

    private String findProcessInstanceForDocument(UUID documentId) {
        // 1. Direct document_id match
        try {
            return jdbc.queryForObject("""
                SELECT process_instance_id FROM ecm_workflow.workflow_instance_records
                WHERE document_id = ? LIMIT 1
                """, String.class, documentId);
        } catch (Exception ignored) {}

        // 2. Match via original_filename → submission ID pattern
        // Documents created from forms have filenames like "form-key-{submissionId}.pdf"
        try {
            String filename = jdbc.queryForObject(
                    "SELECT original_filename FROM ecm_core.documents WHERE id = ?",
                    String.class, documentId);
            if (filename != null) {
                // Extract UUID from filename pattern: "form-key-{uuid}.pdf"
                // Find the last UUID-like segment (8-4-4-4-12 hex pattern)
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})")
                        .matcher(filename);
                String submissionId = null;
                while (m.find()) submissionId = m.group(1); // last UUID in filename
                if (submissionId != null) {
                    return jdbc.queryForObject("""
                        SELECT process_instance_id FROM ecm_workflow.workflow_instance_records
                        WHERE submission_id = ? LIMIT 1
                        """, String.class, submissionId);
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    private String findProcessInstanceForSubmission(String submissionId) {
        try {
            return jdbc.queryForObject("""
                SELECT process_instance_id FROM ecm_workflow.workflow_instance_records
                WHERE submission_id = ? LIMIT 1
                """, String.class, submissionId);
        } catch (Exception e) {
            return null;
        }
    }

    private String findSubmissionForProcess(String processInstanceId) {
        try {
            return jdbc.queryForObject("""
                SELECT submission_id FROM ecm_workflow.workflow_instance_records
                WHERE process_instance_id = ? AND submission_id IS NOT NULL LIMIT 1
                """, String.class, processInstanceId);
        } catch (Exception e) {
            return null;
        }
    }

    private void addCaseNotesForDocument(List<TimelineEvent> events, UUID documentId) {
        try {
            // Find case that has this document in its checklist
            List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT c.id, c.metadata
                FROM ecm_core.cases c
                JOIN ecm_core.case_documents cd ON cd.case_id = c.id
                WHERE cd.document_id = ?
                LIMIT 1
                """, documentId);

            for (Map<String, Object> caseRow : cases) {
                Object metadataObj = caseRow.get("metadata");
                if (metadataObj == null) continue;

                String metadataStr = metadataObj.toString();
                // Parse the JSONB metadata to extract notes array
                try {
                    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    var metadata = mapper.readTree(metadataStr);
                    var notes = metadata.get("notes");
                    if (notes != null && notes.isArray()) {
                        for (var note : notes) {
                            String noteText = note.has("note") ? note.get("note").asText() : "";
                            String author = note.has("author") ? note.get("author").asText() : "";
                            String timestamp = note.has("timestamp") ? note.get("timestamp").asText() : "";

                            OffsetDateTime noteTs = null;
                            try { noteTs = OffsetDateTime.parse(timestamp); } catch (Exception ignored) {}

                            events.add(new TimelineEvent(
                                    "CASE_NOTE",
                                    "Case note",
                                    author,
                                    noteText,
                                    null,
                                    noteTs
                            ));
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse case metadata for notes: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.debug("Could not fetch case notes for document {}: {}", documentId, e.getMessage());
        }
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static OffsetDateTime ts(Object o) {
        if (o instanceof OffsetDateTime odt) return odt;
        if (o instanceof java.sql.Timestamp t) return t.toInstant().atOffset(java.time.ZoneOffset.UTC);
        return null;
    }
}
