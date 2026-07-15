package com.ecm.ocr.pipeline;

import com.ecm.ocr.engine.EngineContext.FewShotExample;
import com.ecm.ocr.engine.OcrEngineResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.util.*;

/**
 * Manages few-shot training examples for GLM-OCR.
 *
 * <p>When Azure AI produces high-quality results (used as fallback), this service
 * saves the structured output as a training example. Next time GLM-OCR processes
 * a similar document, the example is included in the prompt for better accuracy.</p>
 *
 * <p>Uses {@code ecm_admin.training_examples} with lifecycle management:
 * CANDIDATE → VERIFIED → ACTIVE → RETIRED. Only human-approved examples
 * (ACTIVE/VERIFIED) are used in prompts, with fallback to CANDIDATE if none exist.</p>
 *
 * <h3>Storage:</h3>
 * <pre>
 * ecm_admin.training_examples (
 *   id, category_code, source, status, expected_fields, document_hash,
 *   sample_document_id, accuracy_score, times_used, times_correct,
 *   verified_by, verified_at, created_at, created_by
 * )
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlmTrainingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private static final int MAX_EXAMPLES_PER_CATEGORY = 10;

    /** Cached flag: whether training_examples table exists (null = not checked yet) */
    private volatile Boolean newTableExists = null;

    /**
     * Save an Azure result as a training example for GLM-OCR.
     * Inserts into training_examples with status=CANDIDATE (not ACTIVE).
     * Falls back to glm_ocr_examples if the new table doesn't exist yet.
     *
     * @param categoryCode  document category
     * @param result        the Azure engine result to learn from
     * @param documentBytes document bytes (hashed for deduplication)
     * @param source        "AZURE" or "MANUAL"
     */
    public void saveExample(String categoryCode, OcrEngineResult result,
                            byte[] documentBytes, String source) {
        saveExample(categoryCode, result, documentBytes, source, null);
    }

    /**
     * Save an Azure result as a training example for GLM-OCR.
     *
     * @param categoryCode    document category
     * @param result          the Azure engine result to learn from
     * @param documentBytes   document bytes (hashed for deduplication)
     * @param source          "AZURE" or "MANUAL"
     * @param sampleDocumentId optional reference to the original document
     */
    public void saveExample(String categoryCode, OcrEngineResult result,
                            byte[] documentBytes, String source, UUID sampleDocumentId) {
        if (categoryCode == null || result.fields() == null || result.fields().isEmpty()) {
            return;
        }

        try {
            String hash = sha256(documentBytes);

            if (useNewTable()) {
                saveToTrainingExamples(categoryCode, result, hash, source, sampleDocumentId);
            } else {
                saveToLegacyTable(categoryCode, result, hash, source);
            }

        } catch (Exception e) {
            log.warn("Failed to save GLM training example: {}", e.getMessage());
        }
    }

    private void saveToTrainingExamples(String categoryCode, OcrEngineResult result,
                                        String hash, String source, UUID sampleDocumentId) throws Exception {
        // Check for duplicates
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecm_admin.training_examples WHERE document_hash = ?",
                Integer.class, hash);
        if (existing != null && existing > 0) {
            log.debug("Training example already exists for document hash {}", hash);
            return;
        }

        // Build expected_fields JSON
        Map<String, Object> expectedFields = buildExpectedFields(result);
        String fieldsJson = objectMapper.writeValueAsString(expectedFields);

        // Limit examples per category — remove oldest CANDIDATE if at capacity
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecm_admin.training_examples WHERE category_code = ?",
                Integer.class, categoryCode);
        if (count != null && count >= MAX_EXAMPLES_PER_CATEGORY) {
            jdbc.update(
                    "DELETE FROM ecm_admin.training_examples WHERE id = (" +
                    "  SELECT id FROM ecm_admin.training_examples " +
                    "  WHERE category_code = ? AND status = 'CANDIDATE' " +
                    "  ORDER BY created_at ASC LIMIT 1)",
                    categoryCode);
        }

        jdbc.update(
                "INSERT INTO ecm_admin.training_examples " +
                "(category_code, source, status, expected_fields, document_hash, sample_document_id, created_by) " +
                "VALUES (?, ?, 'CANDIDATE', ?::jsonb, ?, ?, ?)",
                categoryCode, source, fieldsJson, hash,
                sampleDocumentId, "system:" + source.toLowerCase());

        log.info("Saved training example: category={}, fields={}, source={}, status=CANDIDATE",
                categoryCode, expectedFields.size(), source);
    }

    /** Legacy fallback — writes to glm_ocr_examples if training_examples table doesn't exist */
    private void saveToLegacyTable(String categoryCode, OcrEngineResult result,
                                   String hash, String source) throws Exception {
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecm_admin.glm_ocr_examples WHERE document_hash = ?",
                Integer.class, hash);
        if (existing != null && existing > 0) {
            log.debug("Training example already exists for document hash {}", hash);
            return;
        }

        Map<String, Object> expectedOutput = new LinkedHashMap<>();
        if (result.detectedCategory() != null) {
            expectedOutput.put("category", result.detectedCategory());
        }
        if (result.confidence() != null) {
            expectedOutput.put("confidence", result.confidence());
        }
        Map<String, Object> cleanFields = buildCleanFields(result);
        expectedOutput.put("fields", cleanFields);

        String outputJson = objectMapper.writeValueAsString(expectedOutput);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ecm_admin.glm_ocr_examples WHERE category_code = ?",
                Integer.class, categoryCode);
        if (count != null && count >= MAX_EXAMPLES_PER_CATEGORY) {
            jdbc.update(
                    "DELETE FROM ecm_admin.glm_ocr_examples WHERE id = (" +
                    "  SELECT id FROM ecm_admin.glm_ocr_examples " +
                    "  WHERE category_code = ? ORDER BY created_at ASC LIMIT 1)",
                    categoryCode);
        }

        jdbc.update(
                "INSERT INTO ecm_admin.glm_ocr_examples " +
                "(category_code, source, document_hash, expected_output, confidence, created_by) " +
                "VALUES (?, ?, ?, ?::jsonb, ?, ?)",
                categoryCode, source, hash, outputJson,
                result.confidence(), "system:" + source.toLowerCase());

        log.info("Saved GLM training example (legacy): category={}, fields={}, source={}",
                categoryCode, cleanFields.size(), source);
    }

    /**
     * Load few-shot examples for a category (or all categories if null).
     * Reads from training_examples WHERE status IN ('ACTIVE', 'VERIFIED').
     * Falls back to CANDIDATE if no approved examples exist, or to glm_ocr_examples
     * if the new table doesn't exist.
     *
     * @param categoryCode category filter (null = load diverse examples)
     * @param maxExamples  max number of combined examples to return
     * @return list of few-shot examples for prompt injection
     */
    @SuppressWarnings("unchecked")
    public List<FewShotExample> loadExamples(String categoryCode, int maxExamples) {
        try {
            if (useNewTable()) {
                return loadFromTrainingExamples(categoryCode, maxExamples);
            } else {
                return loadFromLegacyTable(categoryCode, maxExamples);
            }
        } catch (Exception e) {
            log.debug("Could not load GLM training examples: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<FewShotExample> loadFromTrainingExamples(String categoryCode, int maxExamples) throws Exception {
        List<Map<String, Object>> rows;

        // First try ACTIVE/VERIFIED examples
        if (categoryCode != null) {
            rows = jdbc.queryForList(
                    "SELECT category_code, source, expected_fields FROM ecm_admin.training_examples " +
                    "WHERE category_code = ? AND status IN ('ACTIVE', 'VERIFIED') " +
                    "ORDER BY accuracy_score DESC NULLS LAST, created_at DESC LIMIT 20",
                    categoryCode);
        } else {
            rows = jdbc.queryForList(
                    "SELECT category_code, source, expected_fields FROM ecm_admin.training_examples " +
                    "WHERE status IN ('ACTIVE', 'VERIFIED') " +
                    "ORDER BY accuracy_score DESC NULLS LAST, created_at DESC LIMIT 20");
        }

        // Fall back to CANDIDATE if no approved examples exist
        if (rows.isEmpty()) {
            if (categoryCode != null) {
                rows = jdbc.queryForList(
                        "SELECT category_code, source, expected_fields FROM ecm_admin.training_examples " +
                        "WHERE category_code = ? AND status = 'CANDIDATE' " +
                        "ORDER BY created_at DESC LIMIT 20",
                        categoryCode);
            } else {
                rows = jdbc.queryForList(
                        "SELECT category_code, source, expected_fields FROM ecm_admin.training_examples " +
                        "WHERE status = 'CANDIDATE' " +
                        "ORDER BY created_at DESC LIMIT 20");
            }
        }

        if (rows.isEmpty()) return List.of();

        return mergeExamples(rows, maxExamples, "expected_fields");
    }

    @SuppressWarnings("unchecked")
    private List<FewShotExample> loadFromLegacyTable(String categoryCode, int maxExamples) throws Exception {
        List<Map<String, Object>> rows;
        if (categoryCode != null) {
            rows = jdbc.queryForList(
                    "SELECT category_code, source, expected_output FROM ecm_admin.glm_ocr_examples " +
                    "WHERE category_code = ? ORDER BY confidence DESC NULLS LAST LIMIT 20",
                    categoryCode);
        } else {
            rows = jdbc.queryForList(
                    "SELECT category_code, source, expected_output FROM ecm_admin.glm_ocr_examples " +
                    "ORDER BY confidence DESC NULLS LAST LIMIT 20");
        }

        if (rows.isEmpty()) return List.of();

        return mergeExamples(rows, maxExamples, "expected_output");
    }

    /**
     * Merge rows into combined few-shot examples, one per category.
     * The jsonColumn parameter handles both legacy (expected_output with nested fields)
     * and new (expected_fields which IS the fields map directly) formats.
     */
    @SuppressWarnings("unchecked")
    private List<FewShotExample> mergeExamples(List<Map<String, Object>> rows,
                                                int maxExamples, String jsonColumn) throws Exception {
        Map<String, Map<String, Object>> mergedByCategory = new LinkedHashMap<>();

        for (Map<String, Object> row : rows) {
            String cat = (String) row.get("category_code");
            String json = row.get(jsonColumn).toString();

            try {
                Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
                // New table: expected_fields is the fields map directly
                // Legacy table: expected_output has { category, confidence, fields: {...} }
                Map<String, Object> fields;
                if (parsed.containsKey("fields") && parsed.get("fields") instanceof Map) {
                    fields = (Map<String, Object>) parsed.get("fields");
                } else {
                    fields = parsed; // expected_fields is the fields map itself
                }

                Map<String, Object> merged = mergedByCategory.computeIfAbsent(cat, k -> new LinkedHashMap<>());
                fields.forEach(merged::putIfAbsent);
            } catch (Exception e) {
                log.debug("Skipping malformed training example: {}", e.getMessage());
            }
        }

        // Build combined examples — synthesize full_name if missing
        List<FewShotExample> result = new ArrayList<>();
        for (var entry : mergedByCategory.entrySet()) {
            if (result.size() >= maxExamples) break;
            try {
                Map<String, Object> mergedFields = entry.getValue();
                if (!mergedFields.containsKey("full_name")) {
                    String fullName = synthesizeFullName(mergedFields);
                    if (fullName != null) mergedFields.put("full_name", fullName);
                }
                Map<String, Object> combined = new LinkedHashMap<>();
                combined.put("category", entry.getKey());
                combined.put("fields", mergedFields);
                result.add(new FewShotExample(entry.getKey(), objectMapper.writeValueAsString(combined)));
            } catch (Exception e) {
                log.debug("Failed to serialize combined example: {}", e.getMessage());
            }
        }

        log.debug("Loaded {} combined training examples (from {} rows)", result.size(), rows.size());
        return result;
    }

    /**
     * Increment the usage counter for a training example.
     * Called when an example is injected into a prompt.
     *
     * @param exampleId training example ID
     */
    public void incrementUsage(Long exampleId) {
        if (exampleId == null) return;
        try {
            if (useNewTable()) {
                jdbc.update(
                        "UPDATE ecm_admin.training_examples SET times_used = times_used + 1 WHERE id = ?",
                        exampleId);
            }
        } catch (Exception e) {
            log.debug("Failed to increment usage for example {}: {}", exampleId, e.getMessage());
        }
    }

    /**
     * Record whether a training example produced a correct result.
     * Updates times_correct and recalculates accuracy_score.
     *
     * @param exampleId training example ID
     * @param correct   whether the downstream extraction matched expectations
     */
    public void recordAccuracy(Long exampleId, boolean correct) {
        if (exampleId == null) return;
        try {
            if (useNewTable()) {
                if (correct) {
                    jdbc.update(
                            "UPDATE ecm_admin.training_examples " +
                            "SET times_correct = times_correct + 1, " +
                            "    accuracy_score = CASE WHEN times_used > 0 " +
                            "        THEN ((times_correct + 1)::decimal / times_used) * 100 " +
                            "        ELSE NULL END " +
                            "WHERE id = ?",
                            exampleId);
                } else {
                    jdbc.update(
                            "UPDATE ecm_admin.training_examples " +
                            "SET accuracy_score = CASE WHEN times_used > 0 " +
                            "        THEN (times_correct::decimal / times_used) * 100 " +
                            "        ELSE NULL END " +
                            "WHERE id = ?",
                            exampleId);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to record accuracy for example {}: {}", exampleId, e.getMessage());
        }
    }

    /**
     * Weekly job: retire training examples with poor accuracy.
     * Runs every Monday at 2:00 AM.
     * Retires examples where times_used > 20 AND accuracy_score < 60.
     */
    @Scheduled(cron = "0 0 2 * * MON")
    public void retireStaleExamples() {
        try {
            if (!useNewTable()) return;

            int retired = jdbc.update(
                    "UPDATE ecm_admin.training_examples " +
                    "SET status = 'RETIRED' " +
                    "WHERE status IN ('ACTIVE', 'VERIFIED', 'CANDIDATE') " +
                    "  AND times_used > 20 AND accuracy_score < 60");

            if (retired > 0) {
                log.info("Retired {} stale training examples (times_used > 20, accuracy < 60%)", retired);
            }
        } catch (Exception e) {
            log.warn("Failed to retire stale training examples: {}", e.getMessage());
        }
    }

    // ─── Private Helpers ──────────────────────────────────────────────────────

    /** Build the expected_fields map (just the clean fields, no category/confidence wrapper) */
    private Map<String, Object> buildExpectedFields(OcrEngineResult result) {
        Map<String, Object> cleanFields = buildCleanFields(result);
        // For training_examples, expected_fields stores category + fields together
        Map<String, Object> expectedFields = new LinkedHashMap<>();
        if (result.detectedCategory() != null) {
            expectedFields.put("category", result.detectedCategory());
        }
        if (result.confidence() != null) {
            expectedFields.put("confidence", result.confidence());
        }
        expectedFields.put("fields", cleanFields);
        return expectedFields;
    }

    /** Extract clean (non-internal, non-blank) fields from result, synthesize full_name if needed */
    private Map<String, Object> buildCleanFields(OcrEngineResult result) {
        Map<String, Object> cleanFields = new LinkedHashMap<>();
        result.fields().forEach((k, v) -> {
            if (!k.startsWith("_") && v != null && !v.toString().isBlank()) {
                cleanFields.put(k, v);
            }
        });
        if (!cleanFields.containsKey("full_name")) {
            String fullName = synthesizeFullName(cleanFields);
            if (fullName != null) cleanFields.put("full_name", fullName);
        }
        return cleanFields;
    }

    /**
     * Check whether ecm_admin.training_examples table exists.
     * Result is cached after first check to avoid repeated queries.
     */
    private boolean useNewTable() {
        if (newTableExists != null) return newTableExists;
        try {
            jdbc.queryForObject(
                    "SELECT 1 FROM ecm_admin.training_examples LIMIT 0", Integer.class);
            newTableExists = true;
        } catch (Exception e) {
            log.info("training_examples table not found, using legacy glm_ocr_examples");
            newTableExists = false;
        }
        return newTableExists;
    }

    /**
     * Compose full_name from first_name + middle_name + last_name.
     * Also handles passenger_name, borrower_name, candidate_name variants.
     */
    private static String synthesizeFullName(Map<String, Object> fields) {
        String first = fieldStr(fields, "first_name");
        String middle = fieldStr(fields, "middle_name");
        String last = fieldStr(fields, "last_name");

        if (first == null && last == null) return null;

        StringBuilder sb = new StringBuilder();
        if (first != null) sb.append(first);
        if (middle != null) sb.append(" ").append(middle);
        if (last != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(last);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    private static String fieldStr(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        return v != null && !v.toString().isBlank() ? v.toString().trim() : null;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString(); // fallback — no dedup but won't crash
        }
    }
}
