package com.ecm.batch.controller;

import com.ecm.batch.dto.BatchItemResponse;
import com.ecm.batch.dto.ReviewRequest;
import com.ecm.batch.service.BatchReviewService;
import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/batch/review")
@RequiredArgsConstructor
@Slf4j
public class BatchReviewController {

    private final BatchReviewService batchReviewService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<Page<BatchItemResponse>>> getReviewQueue(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BatchItemResponse> items = batchReviewService.getReviewQueue(pageable);
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @GetMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<BatchItemResponse>> getReviewItem(@PathVariable UUID itemId) {
        BatchItemResponse item = batchReviewService.getReviewItem(itemId);
        return ResponseEntity.ok(ApiResponse.ok(item));
    }

    @PutMapping("/{itemId}")
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<BatchItemResponse>> approveItem(
            @PathVariable UUID itemId,
            @RequestBody ReviewRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String reviewedBy = jwt.getClaimAsString("email");
        if (reviewedBy == null) reviewedBy = jwt.getSubject();

        BatchItemResponse response = batchReviewService.approveItem(itemId, request, reviewedBy);
        return ResponseEntity.ok(ApiResponse.ok(response, "Item approved"));
    }

    @PostMapping("/{itemId}/flag")
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<BatchItemResponse>> flagItem(
            @PathVariable UUID itemId,
            @RequestBody Map<String, String> body,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String reviewedBy = jwt.getClaimAsString("email");
        if (reviewedBy == null) reviewedBy = jwt.getSubject();

        String notes = body.getOrDefault("notes", "Flagged for re-review");
        BatchItemResponse response = batchReviewService.flagItem(itemId, notes, reviewedBy);
        return ResponseEntity.ok(ApiResponse.ok(response, "Item flagged"));
    }

    /** Retry a failed item — reconciles first, re-queues if not yet classified. */
    @PostMapping("/{itemId}/retry")
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<BatchItemResponse>> retryItem(
            @PathVariable UUID itemId,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String retriedBy = jwt.getClaimAsString("email");
        if (retriedBy == null) retriedBy = jwt.getSubject();

        BatchItemResponse response = batchReviewService.retryItem(itemId, retriedBy);
        return ResponseEntity.ok(ApiResponse.ok(response, "Item retried"));
    }

    /** Reconcile a batch item with its document's current state. */
    @PostMapping("/{itemId}/reconcile")
    @PreAuthorize("hasPermission(null, 'batch:review')")
    public ResponseEntity<ApiResponse<BatchItemResponse>> reconcileItem(@PathVariable UUID itemId) {
        BatchItemResponse response = batchReviewService.reconcileItem(itemId);
        return ResponseEntity.ok(ApiResponse.ok(response, "Item reconciled"));
    }
}
