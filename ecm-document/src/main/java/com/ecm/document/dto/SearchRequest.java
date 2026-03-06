package com.ecm.document.dto;

import java.util.List;

/**
 * Full-text document search request.
 *
 * q            — free text query (searches documentName + extractedText + extractedFields)
 * categoryIds  — facet filter: list of category IDs (OR between them)
 * segmentIds   — facet filter: list of segment IDs
 * statuses     — facet filter: e.g. ["ACTIVE","ARCHIVED"]
 * mimeTypes    — facet filter: e.g. ["application/pdf"]
 * from / to    — ISO date strings for uploadedAt range filter
 * page / size  — pagination
 * highlight    — if true, response includes <em>-wrapped match snippets
 */
public record SearchRequest(
        String       q,
        List<Integer> categoryIds,
        List<Integer> segmentIds,
        List<String>  statuses,
        List<String>  mimeTypes,
        String        from,
        String        to,
        int           page,
        int           size,
        boolean       highlight
) {
    public SearchRequest {
        if (size <= 0 || size > 100) size = 20;
        if (page < 0) page = 0;
    }
}