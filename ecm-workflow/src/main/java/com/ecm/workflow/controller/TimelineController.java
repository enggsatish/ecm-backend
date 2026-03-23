package com.ecm.workflow.controller;

import com.ecm.common.model.ApiResponse;
import com.ecm.workflow.dto.TimelineEvent;
import com.ecm.workflow.service.TimelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Unified timeline API — aggregates events across form submissions,
 * workflow instances, task history, and documents.
 *
 * GET /api/workflow/timeline/document/{documentId}
 * GET /api/workflow/timeline/submission/{submissionId}
 */
@RestController
@RequestMapping("/api/workflow/timeline")
@RequiredArgsConstructor
public class TimelineController {

    private final TimelineService timelineService;

    @GetMapping("/document/{documentId}")
    @PreAuthorize("hasPermission(null, 'workflow:view')")
    public ResponseEntity<ApiResponse<List<TimelineEvent>>> getDocumentTimeline(
            @PathVariable UUID documentId) {
        return ResponseEntity.ok(ApiResponse.ok(
                timelineService.getTimelineForDocument(documentId)));
    }

    @GetMapping("/submission/{submissionId}")
    @PreAuthorize("hasPermission(null, 'workflow:view')")
    public ResponseEntity<ApiResponse<List<TimelineEvent>>> getSubmissionTimeline(
            @PathVariable UUID submissionId) {
        return ResponseEntity.ok(ApiResponse.ok(
                timelineService.getTimelineForSubmission(submissionId)));
    }
}
