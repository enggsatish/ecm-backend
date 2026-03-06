package com.ecm.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.action.update.UpdateRequest;
import org.opensearch.client.RequestOptions;
import org.opensearch.client.RestHighLevelClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Syncs document status changes back to OpenSearch so search results
 * reflect the current lifecycle state without waiting for a full re-index.
 *
 * Called by DocumentServiceImpl whenever status changes:
 *   ACTIVE → ARCHIVED  (archive endpoint)
 *   any    → DELETED   (delete endpoint)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentIndexSyncService {

    private final RestHighLevelClient openSearch;

    @Value("${ecm.opensearch.index-name:ecm-documents}")
    private String indexName;

    public void updateStatus(UUID documentId, String newStatus) {
        try {
            UpdateRequest req = new UpdateRequest(indexName, documentId.toString())
                    .doc(Map.of("status", newStatus));
            openSearch.update(req, RequestOptions.DEFAULT);
            log.debug("OpenSearch status updated: documentId={}, status={}", documentId, newStatus);
        } catch (Exception e) {
            // Non-fatal — log and continue
            log.warn("OpenSearch status sync failed for {}: {}", documentId, e.getMessage());
        }
    }
}