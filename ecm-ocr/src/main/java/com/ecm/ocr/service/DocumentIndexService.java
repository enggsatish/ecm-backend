package com.ecm.ocr.service;

import com.ecm.ocr.event.OcrRequestMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.opensearch.common.xcontent.XContentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Indexes OCR extraction results into OpenSearch.
 *
 * Document structure stored per-record:
 *   documentId, documentName, contentType, uploadedBy, categoryId,
 *   extractedText, extractedFields, indexedAt
 *
 * Index: ecm-documents (configured in application.yml)
 * Failures are logged as WARN and do NOT fail the pipeline (graceful degradation).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIndexService {

    private final RestHighLevelClient openSearchClient;  // single bean now — no ambiguity

    @Value("${ecm.ocr.opensearch-index:ecm-documents}")
    private String indexName;

    public void index(OcrRequestMessage msg, String extractedText,
                      Map<String, Object> extractedFields) {
        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("documentId",      msg.documentId());
            doc.put("documentName",    msg.documentName());
            doc.put("contentType",     msg.contentType());
            doc.put("uploadedBy",      msg.uploadedBy());
            doc.put("categoryId",      msg.categoryId());
            doc.put("extractedText",   extractedText);
            doc.put("extractedFields", extractedFields);
            doc.put("indexedAt",       Instant.now().toString());

            String json = new ObjectMapper().writeValueAsString(doc);

            IndexRequest request = new IndexRequest(indexName)
                    .id(msg.documentId().toString())
                    .source(json, XContentType.JSON);

            openSearchClient.index(request, RequestOptions.DEFAULT);
            log.debug("OpenSearch indexed: documentId={}", msg.documentId());

        } catch (IOException e) {
            // Graceful degradation — indexing failure does not fail the OCR pipeline
            log.warn("OpenSearch indexing failed for documentId={}: {}", msg.documentId(), e.getMessage());
        }
    }
}