package com.ecm.batch.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "batch_jobs", schema = "ecm_batch")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchJob {

    @Id
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 30, nullable = false)
    @Builder.Default
    private BatchJobStatus status = BatchJobStatus.CREATED;

    /** Source of the batch: UPLOAD, WATCH_FOLDER, API */
    @Column(name = "source", length = 30)
    private String source;

    @Column(name = "total_items")
    @Builder.Default
    private Integer totalItems = 0;

    @Column(name = "processed_items")
    @Builder.Default
    private Integer processedItems = 0;

    @Column(name = "auto_filed")
    @Builder.Default
    private Integer autoFiled = 0;

    @Column(name = "sent_to_review")
    @Builder.Default
    private Integer sentToReview = 0;

    @Column(name = "failed_items")
    @Builder.Default
    private Integer failedItems = 0;

    @Column(name = "notes", columnDefinition = "text")
    private String notes;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
