package com.ecm.document.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.document.dto.SearchRequest;
import com.ecm.document.dto.SearchResponse;
import com.ecm.document.service.DocumentSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Document full-text search endpoints.
 *
 * GET  /api/documents/search          — search with all filters
 * POST /api/documents/search          — same but filters in body (for complex queries)
 * GET  /api/documents/search/suggest  — type-ahead suggestions
 *
 * Access: any authenticated user. ECM_READONLY sees metadata only (no extractedFields).
 */
@RestController
@RequestMapping("/api/documents/search")
@RequiredArgsConstructor
public class DocumentSearchController {

    private final DocumentSearchService searchService;

    /**
     * GET-based search — good for simple queries and browser bookmarking.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SearchResponse>> searchGet(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Integer> categoryId,
            @RequestParam(required = false) List<Integer> segmentId,
            @RequestParam(required = false) List<String>  status,
            @RequestParam(required = false) List<String>  mimeType,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "true") boolean highlight) {

        SearchRequest req = new SearchRequest(q, categoryId, segmentId,
                status, mimeType, from, to, page, size, highlight);
        return ResponseEntity.ok(ApiResponse.ok(searchService.search(req)));
    }

    /**
     * POST-based search — for complex filter combinations from UI.
     */
    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<SearchResponse>> searchPost(
            @RequestBody SearchRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(searchService.search(req)));
    }

    /**
     * Type-ahead suggestions — returns up to 8 document names matching the prefix.
     * GET /api/documents/search/suggest?q=mortg
     */
    @GetMapping("/suggest")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<String>>> suggest(
            @RequestParam String q) {
        // Simple prefix search on documentName
        SearchRequest req = new SearchRequest(q, null, null, null, null,
                null, null, 0, 8, false);
        SearchResponse res = searchService.search(req);
        List<String> names = res.hits().stream()
                .map(SearchResponse.SearchHit::documentName)
                .distinct()
                .limit(8)
                .toList();
        return ResponseEntity.ok(ApiResponse.ok(names));
    }
}