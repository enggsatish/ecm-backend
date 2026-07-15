package com.ecm.document.service;

import com.ecm.document.dto.DocumentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves hierarchy IDs (categoryId, segmentId, productLineId) to display names
 * by querying the ecm_admin schema. Uses JdbcTemplate for cross-schema reads.
 *
 * Designed for batch enrichment — one SQL per dimension, not N+1.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HierarchyEnricher {

    private final JdbcTemplate jdbc;

    /**
     * Enriches a list of DocumentResponse records with resolved hierarchy names.
     * Returns new record instances (records are immutable).
     */
    public List<DocumentResponse> enrich(List<DocumentResponse> docs) {
        if (docs == null || docs.isEmpty()) return docs;

        // Collect distinct IDs
        Set<Integer> categoryIds = docs.stream()
                .map(DocumentResponse::categoryId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> segmentIds = docs.stream()
                .map(DocumentResponse::segmentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Integer> productLineIds = docs.stream()
                .map(DocumentResponse::productLineId).filter(Objects::nonNull).collect(Collectors.toSet());

        // Batch-resolve names
        Map<Integer, String> categoryNames = resolveCategoryNames(categoryIds);
        Map<Integer, String> segmentNames = resolveSegmentNames(segmentIds);
        Map<Integer, String> productLineNames = resolveProductLineNames(productLineIds);

        // Rebuild records with names
        return docs.stream().map(doc -> new DocumentResponse(
                doc.id(), doc.name(), doc.originalFilename(), doc.mimeType(), doc.fileSizeBytes(),
                doc.categoryId(),
                doc.categoryId() != null ? categoryNames.getOrDefault(doc.categoryId(), doc.categoryName()) : doc.categoryName(),
                doc.departmentId(), doc.uploadedByEmail(), doc.status(), doc.version(),
                doc.parentDocId(), doc.isLatestVersion(), doc.ocrCompleted(),
                doc.extractedText(), doc.extractedFields(), doc.tags(),
                doc.classificationSource(), doc.classificationConfidence(), doc.lockType(),
                doc.createdAt(), doc.updatedAt(),
                doc.segmentId(),
                doc.segmentId() != null ? segmentNames.getOrDefault(doc.segmentId(), doc.segmentName()) : doc.segmentName(),
                doc.productLineId(),
                doc.productLineId() != null ? productLineNames.getOrDefault(doc.productLineId(), doc.productLineName()) : doc.productLineName(),
                doc.downloadUrl(), doc.partyExternalId(),
                doc.lockedBy(), doc.lockedAt(), doc.lockExpiresAt(),
                doc.pipelineState(),
                doc.linkedCaseId(), doc.linkedCaseAssignee()
        )).toList();
    }

    /** Enrich a single document response. */
    public DocumentResponse enrich(DocumentResponse doc) {
        if (doc == null) return null;
        List<DocumentResponse> result = enrich(List.of(doc));
        return result.isEmpty() ? doc : result.getFirst();
    }

    private Map<Integer, String> resolveCategoryNames(Set<Integer> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            return jdbc.query(
                    "SELECT id, name FROM ecm_admin.document_categories WHERE id IN (" + placeholders + ")",
                    ids.toArray(),
                    rs -> {
                        Map<Integer, String> map = new HashMap<>();
                        while (rs.next()) map.put(rs.getInt("id"), rs.getString("name"));
                        return map;
                    }
            );
        } catch (Exception e) {
            log.debug("Could not resolve category names: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Integer, String> resolveSegmentNames(Set<Integer> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            return jdbc.query(
                    "SELECT id, name FROM ecm_admin.segments WHERE id IN (" + placeholders + ")",
                    ids.toArray(),
                    rs -> {
                        Map<Integer, String> map = new HashMap<>();
                        while (rs.next()) map.put(rs.getInt("id"), rs.getString("name"));
                        return map;
                    }
            );
        } catch (Exception e) {
            log.debug("Could not resolve segment names: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<Integer, String> resolveProductLineNames(Set<Integer> ids) {
        if (ids.isEmpty()) return Map.of();
        try {
            String placeholders = ids.stream().map(i -> "?").collect(Collectors.joining(","));
            return jdbc.query(
                    "SELECT id, name FROM ecm_admin.product_lines WHERE id IN (" + placeholders + ")",
                    ids.toArray(),
                    rs -> {
                        Map<Integer, String> map = new HashMap<>();
                        while (rs.next()) map.put(rs.getInt("id"), rs.getString("name"));
                        return map;
                    }
            );
        } catch (Exception e) {
            log.debug("Could not resolve product line names: {}", e.getMessage());
            return Map.of();
        }
    }
}
