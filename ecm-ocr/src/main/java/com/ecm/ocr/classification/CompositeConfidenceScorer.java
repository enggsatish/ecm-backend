package com.ecm.ocr.classification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calculates a multi-signal composite confidence score for document classification.
 * <p>
 * Combines four signals: LLM confidence, field match score, keyword score, and
 * training similarity. Per-category weights and thresholds are loaded from
 * {@code ecm_admin.category_confidence_config} and cached (refreshed every 5 min).
 * <p>
 * Field definitions come from {@code ecm_admin.extraction_templates}.
 */
@Slf4j
@Service
public class CompositeConfidenceScorer {

    private final JdbcTemplate jdbc;

    /** Per-category config cache — refreshed every 5 minutes. */
    private final Map<String, CategoryConfig> configCache = new ConcurrentHashMap<>();

    /** Per-category field templates cache — refreshed every 5 minutes. */
    private final Map<String, List<FieldTemplate>> templateCache = new ConcurrentHashMap<>();

    // ── Default values when no DB config exists ──────────────────────────────

    private static final double DEFAULT_AUTO_ACCEPT  = 85.0;
    private static final double DEFAULT_REVIEW       = 50.0;
    private static final double DEFAULT_REJECT       = 20.0;
    private static final double DEFAULT_WEIGHT_LLM   = 0.30;
    private static final double DEFAULT_WEIGHT_FIELD  = 0.35;
    private static final double DEFAULT_WEIGHT_KW     = 0.20;
    private static final double DEFAULT_WEIGHT_TRAIN  = 0.15;

    private static final CategoryConfig DEFAULT_CONFIG = new CategoryConfig(
            DEFAULT_AUTO_ACCEPT, DEFAULT_REVIEW, DEFAULT_REJECT,
            DEFAULT_WEIGHT_LLM, DEFAULT_WEIGHT_FIELD, DEFAULT_WEIGHT_KW, DEFAULT_WEIGHT_TRAIN
    );

    public CompositeConfidenceScorer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        refreshCache();
    }

    // ── Public records ──────────────────────────────────────────────────────

    public record ScoringInput(
            String categoryCode,
            double llmConfidence,
            Set<String> extractedFields,
            double keywordScore,
            String engineId,
            String modelUsed
    ) {}

    public record ScoringResult(
            double compositeScore,
            double llmComponent,
            double fieldMatchComponent,
            double keywordComponent,
            double trainingSimComponent,
            List<String> matchedRequiredFields,
            List<String> missingRequiredFields,
            Map<String, Object> details
    ) {}

    // ── Internal records ────────────────────────────────────────────────────

    private record CategoryConfig(
            double autoAcceptThreshold,
            double reviewThreshold,
            double rejectThreshold,
            double weightLlm,
            double weightField,
            double weightKeyword,
            double weightTraining
    ) {}

    private record FieldTemplate(String fieldName, boolean required) {}

    // ── Cache refresh (every 5 minutes) ─────────────────────────────────────

    @Scheduled(fixedRate = 300_000)
    public void refreshCache() {
        try {
            loadConfidenceConfig();
            loadExtractionTemplates();
            log.debug("Refreshed confidence config cache: {} categories, {} template categories",
                    configCache.size(), templateCache.size());
        } catch (Exception e) {
            log.warn("Failed to refresh confidence config cache: {}", e.getMessage());
        }
    }

    private void loadConfidenceConfig() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category_code, auto_accept_threshold, review_threshold, reject_threshold, " +
                "weight_llm_confidence, weight_field_match, weight_keyword_score, weight_training_sim " +
                "FROM ecm_admin.category_confidence_config");

        Map<String, CategoryConfig> newCache = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("category_code");
            newCache.put(code, new CategoryConfig(
                    toDouble(row.get("auto_accept_threshold"), DEFAULT_AUTO_ACCEPT),
                    toDouble(row.get("review_threshold"), DEFAULT_REVIEW),
                    toDouble(row.get("reject_threshold"), DEFAULT_REJECT),
                    toDouble(row.get("weight_llm_confidence"), DEFAULT_WEIGHT_LLM),
                    toDouble(row.get("weight_field_match"), DEFAULT_WEIGHT_FIELD),
                    toDouble(row.get("weight_keyword_score"), DEFAULT_WEIGHT_KW),
                    toDouble(row.get("weight_training_sim"), DEFAULT_WEIGHT_TRAIN)
            ));
        }
        configCache.clear();
        configCache.putAll(newCache);
    }

    private void loadExtractionTemplates() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT category_code, field_name, required FROM ecm_admin.extraction_templates");

        Map<String, List<FieldTemplate>> newCache = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String code = (String) row.get("category_code");
            String fieldName = (String) row.get("field_name");
            boolean required = Boolean.TRUE.equals(row.get("required"));
            newCache.computeIfAbsent(code, k -> new ArrayList<>())
                    .add(new FieldTemplate(fieldName, required));
        }
        templateCache.clear();
        templateCache.putAll(newCache);
    }

    // ── Scoring ─────────────────────────────────────────────────────────────

    /**
     * Calculate composite confidence score from multiple signals.
     */
    public ScoringResult score(ScoringInput input) {
        CategoryConfig cfg = configCache.getOrDefault(input.categoryCode(), DEFAULT_CONFIG);
        List<FieldTemplate> templates = templateCache.getOrDefault(input.categoryCode(), List.of());

        // 1. LLM confidence component (0-100 as given)
        double llmComponent = clamp(input.llmConfidence());

        // 2. Field match score
        List<String> matchedRequired = new ArrayList<>();
        List<String> missingRequired = new ArrayList<>();
        double fieldMatchComponent = calculateFieldMatchScore(
                templates, input.extractedFields(), matchedRequired, missingRequired);

        // 3. Keyword component (0-100 as given)
        double keywordComponent = clamp(input.keywordScore());

        // 4. Training similarity (heuristic for now — Phase 4 will add cosine similarity)
        double trainingSim = calculateTrainingSimilarity(input.categoryCode(), fieldMatchComponent);

        // 5. Composite = weighted average
        double composite = llmComponent * cfg.weightLlm()
                + fieldMatchComponent * cfg.weightField()
                + keywordComponent * cfg.weightKeyword()
                + trainingSim * cfg.weightTraining();
        composite = clamp(composite);

        // 6. Build details map
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("compositeScore", round2(composite));
        details.put("llmConfidence", round2(llmComponent));
        details.put("fieldMatchScore", round2(fieldMatchComponent));
        details.put("keywordScore", round2(keywordComponent));
        details.put("trainingSimilarity", round2(trainingSim));
        details.put("weights", Map.of(
                "llm", cfg.weightLlm(), "field", cfg.weightField(),
                "keyword", cfg.weightKeyword(), "training", cfg.weightTraining()));
        details.put("thresholds", Map.of(
                "autoAccept", cfg.autoAcceptThreshold(),
                "review", cfg.reviewThreshold(),
                "reject", cfg.rejectThreshold()));
        details.put("matchedRequiredFields", matchedRequired);
        details.put("missingRequiredFields", missingRequired);
        details.put("resolvedStatus", resolveStatus(input.categoryCode(), composite));
        if (input.engineId() != null) details.put("engineId", input.engineId());
        if (input.modelUsed() != null) details.put("modelUsed", input.modelUsed());

        log.debug("Composite score for {}: {} (llm={}*{}, field={}*{}, kw={}*{}, train={}*{})",
                input.categoryCode(), round2(composite),
                round2(llmComponent), cfg.weightLlm(),
                round2(fieldMatchComponent), cfg.weightField(),
                round2(keywordComponent), cfg.weightKeyword(),
                round2(trainingSim), cfg.weightTraining());

        return new ScoringResult(composite, llmComponent, fieldMatchComponent,
                keywordComponent, trainingSim, matchedRequired, missingRequired, details);
    }

    /**
     * Resolve document status based on composite score and category thresholds.
     *
     * @return "ACTIVE", "NEEDS_REVIEW", or "PENDING_CLASSIFICATION"
     */
    public String resolveStatus(String categoryCode, double compositeScore) {
        CategoryConfig cfg = configCache.getOrDefault(categoryCode, DEFAULT_CONFIG);

        if (compositeScore >= cfg.autoAcceptThreshold()) return "ACTIVE";
        if (compositeScore >= cfg.reviewThreshold()) return "NEEDS_REVIEW";
        return "PENDING_CLASSIFICATION";
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Calculate field match score based on extraction templates.
     * Score = (required_present / total_required) * 80 + (optional_present / total_optional) * 20
     */
    private double calculateFieldMatchScore(List<FieldTemplate> templates,
                                            Set<String> extractedFields,
                                            List<String> matchedRequired,
                                            List<String> missingRequired) {
        if (templates.isEmpty() || extractedFields == null || extractedFields.isEmpty()) {
            return 0.0;
        }

        int totalRequired = 0;
        int requiredPresent = 0;
        int totalOptional = 0;
        int optionalPresent = 0;

        for (FieldTemplate ft : templates) {
            if (ft.required()) {
                totalRequired++;
                if (extractedFields.contains(ft.fieldName())) {
                    requiredPresent++;
                    matchedRequired.add(ft.fieldName());
                } else {
                    missingRequired.add(ft.fieldName());
                }
            } else {
                totalOptional++;
                if (extractedFields.contains(ft.fieldName())) {
                    optionalPresent++;
                }
            }
        }

        double requiredScore = totalRequired > 0
                ? ((double) requiredPresent / totalRequired) * 80.0 : 80.0;
        double optionalScore = totalOptional > 0
                ? ((double) optionalPresent / totalOptional) * 20.0 : 0.0;

        return Math.min(requiredScore + optionalScore, 100.0);
    }

    /**
     * Training similarity heuristic.
     * Proper cosine similarity on embeddings is Phase 4 future work.
     * For now: if training examples exist for the category and field match > 70, similarity = 80.
     */
    private double calculateTrainingSimilarity(String categoryCode, double fieldMatchScore) {
        if (categoryCode == null) return 50.0;

        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_admin.glm_ocr_examples WHERE category_code = ?",
                    Integer.class, categoryCode);
            boolean hasExamples = count != null && count > 0;

            if (hasExamples && fieldMatchScore > 70.0) return 80.0;
            return 50.0;
        } catch (Exception e) {
            log.debug("Could not check training examples for {}: {}", categoryCode, e.getMessage());
            return 50.0;
        }
    }

    private static double toDouble(Object value, double defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
