package com.ecm.document.dto;

import java.util.List;
import java.util.Map;

/**
 * Full-text search result with hits, facets, and optional highlights.
 */
public record SearchResponse(
        List<SearchHit>         hits,
        long                    totalHits,
        int                     page,
        int                     size,
        int                     totalPages,
        Map<String, List<FacetBucket>> facets,   // keyed by field name
        List<String>            suggestions      // type-ahead query suggestions
) {
    public record SearchHit(
            String              documentId,
            String              documentName,
            String              status,
            String              mimeType,
            Integer             categoryId,
            String              categoryCode,
            Integer             segmentId,
            Integer             productLineId,
            String              uploadedBy,
            String              uploadedAt,
            String              highlightedName,     // null if highlight=false
            String              highlightedSnippet,  // excerpt from extractedText
            Map<String, Object> extractedFields,     // all fields from OCR template
            String[]            tags
    ) {}

    public record FacetBucket(
            String key,
            long   docCount
    ) {}
}