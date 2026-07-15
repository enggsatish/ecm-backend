package com.ecm.admin.listener;

import com.ecm.admin.config.AdminRabbitConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Listens for document.classified events published by ecm-document
 * after batch/auto classification assigns a category to a document.
 *
 * If the classified document's partyExternalId matches an active case,
 * and the case's product checklist requires that document category,
 * auto-attaches the document to the first matching unfilled checklist item.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentClassifiedListener {

    private final JdbcTemplate jdbc;

    @RabbitListener(queues = AdminRabbitConfig.Q_DOCUMENT_CLASSIFIED)
    public void onDocumentClassified(Map<String, Object> event) {
        String documentIdStr = str(event.get("documentId"));
        Object categoryIdObj = event.get("categoryId");
        String partyExternalId = str(event.get("partyExternalId"));

        if (documentIdStr == null) {
            log.warn("Ignoring document.classified event — missing documentId: {}", event);
            return;
        }

        UUID documentId;
        try {
            documentId = UUID.fromString(documentIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring document.classified event — invalid documentId: {}", documentIdStr);
            return;
        }

        Integer categoryId = null;
        if (categoryIdObj != null) {
            try {
                categoryId = ((Number) categoryIdObj).intValue();
            } catch (ClassCastException e) {
                try {
                    categoryId = Integer.parseInt(categoryIdObj.toString());
                } catch (NumberFormatException nfe) {
                    log.warn("Could not parse categoryId '{}' for documentId={}", categoryIdObj, documentId);
                }
            }
        }

        log.info("Document classified: documentId={}, categoryId={}, partyExternalId={}",
                documentId, categoryId, partyExternalId);

        if (partyExternalId == null || partyExternalId.isBlank()) {
            log.debug("No partyExternalId for documentId={} — skipping case auto-attach", documentId);
            return;
        }

        if (categoryId == null) {
            log.debug("No categoryId for documentId={} — skipping case auto-attach", documentId);
            return;
        }

        // Skip if document is already linked to a case (uploaded from case UI)
        try {
            int existingLinks = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.case_documents WHERE document_id = ?",
                    Integer.class, documentId);
            if (existingLinks > 0) {
                log.debug("Document {} already linked to a case — skipping auto-attach", documentId);
                return;
            }
        } catch (Exception e) {
            log.warn("Could not check existing case links for documentId={}: {}", documentId, e.getMessage());
        }

        try {
            autoAttachToCase(documentId, categoryId, partyExternalId);
        } catch (Exception e) {
            log.error("Case auto-attach failed for documentId={}: {}", documentId, e.getMessage(), e);
            // Don't rethrow — ACK the message. Document is still standalone.
        }
    }

    /**
     * Find active cases for this party, check if any case's product requires
     * this document category, and auto-link the document to the first matching
     * unfilled checklist item.
     */
    private void autoAttachToCase(UUID documentId, Integer categoryId, String partyExternalId) {
        // 1. Find active cases for this party
        List<Map<String, Object>> activeCases = jdbc.queryForList("""
                SELECT c.id AS case_id, c.product_id
                FROM ecm_core.cases c
                JOIN ecm_core.parties p ON p.id = c.party_id
                WHERE p.external_id = ?
                  AND c.status NOT IN ('COMPLETED', 'CANCELLED', 'REJECTED')
                ORDER BY c.created_at DESC
                """, partyExternalId);

        if (activeCases.isEmpty()) {
            log.debug("No active cases found for partyExternalId={} — document {} remains standalone",
                    partyExternalId, documentId);
            return;
        }

        for (Map<String, Object> caseRow : activeCases) {
            UUID caseId = (UUID) caseRow.get("case_id");

            // 2. Find unfilled checklist items matching this document category
            //    product_document_types links product → category via category_id
            List<Map<String, Object>> matchingItems = jdbc.queryForList("""
                    SELECT cd.id AS checklist_item_id, pdt.id AS pdt_id
                    FROM ecm_core.case_documents cd
                    JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
                    WHERE cd.case_id = ?
                      AND pdt.category_id = ?
                      AND cd.document_id IS NULL
                      AND cd.status IN ('PENDING', 'REJECTED')
                    ORDER BY cd.id
                    LIMIT 1
                    """, caseId, categoryId);

            if (!matchingItems.isEmpty()) {
                Map<String, Object> item = matchingItems.getFirst();
                Integer checklistItemId = ((Number) item.get("checklist_item_id")).intValue();

                int updated = jdbc.update("""
                        UPDATE ecm_core.case_documents
                        SET document_id = ?::uuid,
                            status = 'UPLOADED',
                            uploaded_by = 'system-auto-attach',
                            uploaded_at = NOW(),
                            updated_at = NOW()
                        WHERE id = ? AND case_id = ? AND document_id IS NULL
                        """, documentId.toString(), checklistItemId, caseId);

                if (updated > 0) {
                    log.info("Auto-attached documentId={} to caseId={}, checklistItemId={} (categoryId={})",
                            documentId, caseId, checklistItemId, categoryId);

                    // Record timeline event
                    try {
                        jdbc.update("""
                                INSERT INTO ecm_core.case_timeline_events
                                    (case_id, event_type, description, detail, actor)
                                VALUES (?, 'DOCUMENT_AUTO_ATTACHED',
                                        'Document auto-attached after classification',
                                        ?, 'system')
                                """, caseId, "categoryId=" + categoryId + ", documentId=" + documentId);
                    } catch (Exception e) {
                        log.debug("Timeline record failed for auto-attach — non-fatal: {}", e.getMessage());
                    }
                    return; // attached to first matching case — done
                }
            }
        }

        log.debug("No matching unfilled checklist items found for documentId={} categoryId={} across {} active cases",
                documentId, categoryId, activeCases.size());
    }

    private static String str(Object o) {
        return o != null ? o.toString().trim() : null;
    }
}
