package com.ecm.eforms.listener;

import com.ecm.eforms.model.entity.FormSubmission;
import com.ecm.eforms.repository.FormSubmissionRepository;
import com.ecm.eforms.service.FormDocumentCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;           // ← was Instant (wrong)
import java.util.Map;
import java.util.UUID;

/**
 * Consumes workflow.completed events published by ecm-workflow's
 * FlowableListenersConfig.processCompletedListener().
 *
 * Event payload:
 *   {
 *     "processInstanceId": "...",
 *     "documentId":   "",          ← empty for form-triggered workflows
 *     "decision":     "APPROVED" | "REJECTED",
 *     "comment":      "...",
 *     "submissionId": "uuid"       ← present only for form-triggered workflows
 *   }
 *
 * When submissionId is present:
 *   APPROVED → update FormSubmission.status to "APPROVED" + promote PDF to ecm-document
 *   REJECTED → update FormSubmission.status to "REJECTED"
 *
 * FIXES applied vs the previous generated version:
 *   1. submission.setStatus("APPROVED") — status is a String, not FormSubmission.Status enum
 *   2. submission.setReviewedAt(OffsetDateTime.now()) — field is OffsetDateTime, not Instant
 *   3. isTerminal(String status) — getStatus() returns String, not FormSubmission.Status enum
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowCompletedListener {

    private final FormSubmissionRepository   submissionRepository;
    private final FormDocumentCreationService documentCreationService;

    @RabbitListener(queues = "ecm.eforms.workflow.completed")
    @Transactional
    public void onWorkflowCompleted(Map<String, Object> event) {
        String submissionIdStr = safeStr(event.get("submissionId"));
        String decision        = safeStr(event.get("decision"));
        String comment         = safeStr(event.get("comment"));
        String processId       = safeStr(event.get("processInstanceId"));

        MDC.put("processInstanceId", processId);
        MDC.put("submissionId",      submissionIdStr);

        try {
            // Only act on form-triggered completions (submissionId present)
            if (submissionIdStr == null || submissionIdStr.isBlank()) {
                log.debug("workflow.completed has no submissionId — document workflow, skipping");
                return;
            }

            UUID submissionId = UUID.fromString(submissionIdStr);
            FormSubmission submission = submissionRepository.findById(submissionId)
                    .orElse(null);

            if (submission == null) {
                log.warn("workflow.completed: FormSubmission {} not found — possible duplicate or late event",
                        submissionId);
                return;
            }

            // Guard: only act if not already in a terminal state
            // FIX 3: getStatus() returns String — compare as strings, not enum values
            if (isTerminal(submission.getStatus())) {
                log.info("workflow.completed: submission {} already in state {} — skipping",
                        submissionId, submission.getStatus());
                return;
            }

            if ("APPROVED".equalsIgnoreCase(decision)) {
                // FIX 1: setStatus takes String, not FormSubmission.Status enum
                submission.setStatus("APPROVED");
                submission.setReviewNotes(comment);
                // FIX 2: reviewedAt is OffsetDateTime, not Instant
                submission.setReviewedAt(OffsetDateTime.now());
                submissionRepository.save(submission);

                // Promote to a Document so it appears in the document list
                documentCreationService.createFromApprovedSubmission(submission);

                log.info("FormSubmission {} APPROVED — document promotion triggered", submissionId);

            } else if ("REJECTED".equalsIgnoreCase(decision)) {
                // FIX 1 & 2 same corrections
                submission.setStatus("REJECTED");
                submission.setReviewNotes(comment);
                submission.setReviewedAt(OffsetDateTime.now());
                submissionRepository.save(submission);

                log.info("FormSubmission {} REJECTED", submissionId);

            } else {
                log.warn("workflow.completed: unexpected decision='{}' for submissionId={}",
                        decision, submissionId);
            }

        } catch (IllegalArgumentException e) {
            // Bad UUID format — permanent failure, no retry value
            log.error("workflow.completed: invalid submissionId='{}': {}", submissionIdStr, e.getMessage());
        } catch (Exception e) {
            log.error("workflow.completed: failed for submissionId={}, processId={}: {}",
                    submissionIdStr, processId, e.getMessage(), e);
            throw e;  // rethrow → Spring AMQP retries → DLQ after max attempts
        } finally {
            MDC.clear();
        }
    }

    /**
     * FIX 3: parameter changed from FormSubmission.Status to String.
     * FormSubmission.status is a plain String column, not an @Enumerated column.
     * The inner FormSubmission.Status enum exists only for typed comparisons in
     * switch expressions — it is NOT what getStatus() returns.
     */
    private boolean isTerminal(String status) {
        return "APPROVED".equals(status)
                || "REJECTED".equals(status)
                || "WITHDRAWN".equals(status)
                || "COMPLETED".equals(status);
    }

    private static String safeStr(Object o) {
        if (o == null) return null;
        String s = o.toString().trim();
        return s.isBlank() ? null : s;
    }
}