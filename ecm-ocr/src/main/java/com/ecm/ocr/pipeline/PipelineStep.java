package com.ecm.ocr.pipeline;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single step in the document processing pipeline.
 * Serialized as JSON and appended to the document's pipeline_state column.
 *
 * <p>The UI renders these generically — no hardcoded pipeline shape in the frontend.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PipelineStep(
        String step,     // UPLOAD, OCR, CLASSIFY, FIELDS, CUSTOMER, REVIEW, WORKFLOW, DOCUSIGN
        String status,   // DONE, PENDING, FAILED, SKIPPED, ACTIVE
        String group,    // Visual grouping: ingest, ocr, classify, review, signing
        String label,    // Human-readable label for UI
        String detail,   // Subtitle/detail text
        String ts        // ISO-8601 timestamp
) {

    public static PipelineStep done(String step, String group, String label, String detail) {
        return new PipelineStep(step, "DONE", group, label, detail, Instant.now().toString());
    }

    public static PipelineStep pending(String step, String group, String label) {
        return new PipelineStep(step, "PENDING", group, label, null, null);
    }

    public static PipelineStep failed(String step, String group, String label, String detail) {
        return new PipelineStep(step, "FAILED", group, label, detail, Instant.now().toString());
    }

    public static PipelineStep skipped(String step, String group, String label, String detail) {
        return new PipelineStep(step, "SKIPPED", group, label, detail, Instant.now().toString());
    }

    /** Collects steps incrementally during pipeline processing. */
    public static class Builder {
        private final List<PipelineStep> steps = new ArrayList<>();

        public Builder add(PipelineStep step) { steps.add(step); return this; }
        public List<PipelineStep> build() { return List.copyOf(steps); }
    }
}
