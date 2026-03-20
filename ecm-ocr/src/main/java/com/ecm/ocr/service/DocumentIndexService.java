package com.ecm.ocr.service;

import com.ecm.ocr.event.OcrRequestMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Array;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Indexes OCR extraction results into OpenSearch after enriching with the full
 * document record from ecm_core.documents.
 *
 * ── Why enrichment is needed ─────────────────────────────────────────────────
 * The OcrRequestMessage only carries the fields needed to run OCR (bucket, key,
 * contentType, categoryId, documentName, uploadedBy). It deliberately omits
 * status, segmentId, productLineId, tags, uploadedAt, uploadedByEmail, and
 * partyExternalId to keep the RabbitMQ payload small.
 *
 * DocumentSearchService filters on ALL of those fields. Without them in the
 * index, every filter query returns zero results even when full-text matches
 * exist. This single JdbcTemplate lookup per document fixes that.
 *
 * ── Enrichment strategy ───────────────────────────────────────────────────────
 * At the point index() is called, DocumentWritebackService has already committed
 * extracted_text + extracted_fields + status=ACTIVE to the DB.  So the SELECT
 * here reads the fully-updated row — status will be ACTIVE, not PENDING_OCR.
 *
 * ── Failure behaviour ─────────────────────────────────────────────────────────
 * Graceful degradation at two levels:
 *   1. If the DB enrichment query fails → log WARN, fall back to indexing with
 *      only the message fields (same as before this fix). Never fails the pipeline.
 *   2. If OpenSearch itself is unavailable → log WARN. Never fails the pipeline.
 *
 * Index: ecm-documents (configured via ecm.ocr.opensearch-index in application.yml)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexService {

    private final RestHighLevelClient openSearchClient;
    private final JdbcTemplate        jdbc;
    private final ObjectMapper        objectMapper;

    @Value("${ecm.ocr.opensearch-index:ecm-documents}")
    private String indexName;

    // ── Full enrichment SQL ───────────────────────────────────────────────────
    // Reads only the columns that OpenSearch needs but are absent from the
    // OcrRequestMessage. status is included because writebackService has already
    // updated it to ACTIVE before index() is called.
    private static final String ENRICH_SQL = """
            SELECT status,
                   segment_id,
                   product_line_id,
                   tags,
                   created_at,
                   uploaded_by_email,
                   party_external_id
            FROM ecm_core.documents
            WHERE id = ?
            """;

    /**
     * Build and push the OpenSearch document for the given completed OCR run.
     *
     * @param msg             original OCR request (documentId, name, contentType, …)
     * @param extractedText   full raw text extracted by Tika / Tesseract
     * @param extractedFields structured key→value map from FieldExtractorService
     */
    public void index(OcrRequestMessage msg,
                      String extractedText,
                      Map<String, Object> extractedFields) {
        try {
            // 1. Build base document from the message payload
            Map<String, Object> doc = buildBaseDocument(msg, extractedText, extractedFields);

            // 2. Enrich with DB fields that DocumentSearchService filters on
            enrichFromDatabase(doc, msg.documentId());

            // 3. Serialize and push to OpenSearch
            String json = objectMapper.writeValueAsString(doc);
            IndexRequest request = new IndexRequest(indexName)
                    .id(msg.documentId().toString())
                    .source(json, XContentType.JSON);

            openSearchClient.index(request, RequestOptions.DEFAULT);
            log.debug("OpenSearch indexed: documentId={}, fields={}", msg.documentId(), doc.keySet());

        } catch (IOException e) {
            log.warn("OpenSearch indexing failed for documentId={}: {}",
                    msg.documentId(), e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Base fields available directly from the OcrRequestMessage.
     * These are always populated regardless of whether DB enrichment succeeds.
     */
    private Map<String, Object> buildBaseDocument(OcrRequestMessage msg,
                                                  String extractedText,
                                                  Map<String, Object> extractedFields) {
        Map<String, Object> doc = new HashMap<>();
        doc.put("documentId",      msg.documentId().toString());
        doc.put("documentName",    msg.documentName());
        doc.put("mimeType",        msg.contentType());   // field name matches SearchService
        doc.put("uploadedBy",      msg.uploadedBy());
        doc.put("categoryId",      msg.categoryId());
        doc.put("extractedText",   extractedText != null ? extractedText : "");
        doc.put("extractedFields", extractedFields != null ? extractedFields : Map.of());
        doc.put("indexedAt",       Instant.now().toString());
        // tenantId — hardcoded "default" until multi-tenancy is fully wired.
        // DocumentSearchService always filters by tenantId="default".
        doc.put("tenantId",        "default");
        return doc;
    }

    /**
     * Enriches the OpenSearch document map with fields fetched from
     * ecm_core.documents: status, segmentId, productLineId, tags, uploadedAt,
     * uploadedByEmail, partyExternalId.
     *
     * On any failure (row not found, DB down, type-mapping error) this logs a
     * warning and returns — the base document is still indexed without these fields.
     */
    private void enrichFromDatabase(Map<String, Object> doc, UUID documentId) {
        try {
            jdbc.query(ENRICH_SQL, rs -> {
                // status — ACTIVE after writeback; DELETED excluded by search filter
                doc.put("status",          rs.getString("status"));

                // Hierarchy context — used by facet filters in DocumentSearchService
                int segId = rs.getInt("segment_id");
                if (!rs.wasNull()) doc.put("segmentId",     segId);

                int prodId = rs.getInt("product_line_id");
                if (!rs.wasNull()) doc.put("productLineId", prodId);

                // uploadedAt — used for date-range filter (req.from / req.to)
                // Stored as TIMESTAMPTZ; convert to ISO-8601 string for OpenSearch.
                var createdAt = rs.getTimestamp("created_at");
                if (createdAt != null) {
                    doc.put("uploadedAt", createdAt.toInstant().toString());
                }

                // uploadedByEmail — human-readable uploader; useful for display
                doc.put("uploadedByEmail",   rs.getString("uploaded_by_email"));

                // partyExternalId — customer/party soft link
                doc.put("partyExternalId",   rs.getString("party_external_id"));

                // tags — PostgreSQL TEXT[] → String[]
                // rs.getArray() returns null if the column is NULL, not an empty array.
                Array tagsArray = rs.getArray("tags");
                if (tagsArray != null) {
                    try {
                        String[] tagValues = (String[]) tagsArray.getArray();
                        doc.put("tags", tagValues);
                    } catch (Exception tagEx) {
                        log.debug("Could not read tags array for documentId={}: {}",
                                documentId, tagEx.getMessage());
                    }
                }

            }, documentId);

            log.debug("DB enrichment complete for documentId={}", documentId);

        } catch (EmptyResultDataAccessException e) {
            // Document was deleted between writeback and index — harmless; log only.
            log.warn("DB enrichment skipped — document not found: documentId={}", documentId);
        } catch (Exception e) {
            // DB unavailable, network issue, column mismatch — do not fail indexing.
            log.warn("DB enrichment failed for documentId={}: {} — indexing with base fields only",
                    documentId, e.getMessage());
        }
    }
}