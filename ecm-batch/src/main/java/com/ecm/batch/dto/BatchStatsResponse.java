package com.ecm.batch.dto;

public record BatchStatsResponse(
        long processedToday,
        long autoFiledToday,
        long inReviewToday,
        long failedToday,
        long totalJobs,
        long activeJobs
) {}
