package com.ecm.batch.controller;

import com.ecm.batch.dto.BatchItemResponse;
import com.ecm.batch.dto.BatchJobResponse;
import com.ecm.batch.dto.BatchStatsResponse;
import com.ecm.batch.service.BatchJobService;
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
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
@Slf4j
public class BatchJobController {

    private final BatchJobService batchJobService;

    @PostMapping("/jobs")
    @PreAuthorize("hasPermission(null, 'batch:upload')")
    public ResponseEntity<ApiResponse<BatchJobResponse>> createBatch(
            @RequestParam("files") List<MultipartFile> files,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "notes", required = false) String notes,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String createdBy = jwt.getClaimAsString("email");
        if (createdBy == null) createdBy = jwt.getSubject();

        log.info("Creating batch job with {} files by {}", files.size(), createdBy);
        BatchJobResponse response = batchJobService.createBatch(files, source, notes, createdBy);
        return ResponseEntity.ok(ApiResponse.ok(response, "Batch job created"));
    }

    @GetMapping("/jobs")
    @PreAuthorize("hasPermission(null, 'batch:view')")
    public ResponseEntity<ApiResponse<Page<BatchJobResponse>>> listBatches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BatchJobResponse> jobs = batchJobService.listBatches(pageable);
        return ResponseEntity.ok(ApiResponse.ok(jobs));
    }

    @GetMapping("/jobs/{id}")
    @PreAuthorize("hasPermission(null, 'batch:view')")
    public ResponseEntity<ApiResponse<BatchJobResponse>> getBatch(@PathVariable UUID id) {
        BatchJobResponse response = batchJobService.getBatch(id);
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    @GetMapping("/jobs/{id}/items")
    @PreAuthorize("hasPermission(null, 'batch:view')")
    public ResponseEntity<ApiResponse<Page<BatchItemResponse>>> getJobItems(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BatchItemResponse> items = batchJobService.getJobItems(id, pageable);
        return ResponseEntity.ok(ApiResponse.ok(items));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasPermission(null, 'batch:view')")
    public ResponseEntity<ApiResponse<BatchStatsResponse>> getStats() {
        BatchStatsResponse stats = batchJobService.getStats();
        return ResponseEntity.ok(ApiResponse.ok(stats));
    }

    @GetMapping("/auto-processed")
    @PreAuthorize("hasPermission(null, 'batch:view')")
    public ResponseEntity<ApiResponse<Page<BatchItemResponse>>> getAutoProcessed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PageRequest.of(page, size);
        Page<BatchItemResponse> items = batchJobService.getAutoProcessedItems(pageable);
        return ResponseEntity.ok(ApiResponse.ok(items));
    }
}
