package com.ecm.ocr.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and caches extraction templates from {@code ecm_admin.extraction_templates}.
 *
 * <p>Each template defines which fields are expected for a document category,
 * their types, whether they are required, and their display order. This replaces
 * hardcoded field lists throughout the OCR pipeline.</p>
 *
 * <h3>Consumers:</h3>
 * <ul>
 *   <li>{@code GlmPromptBuilder} — uses field names for targeted extraction prompts</li>
 *   <li>{@code DocumentClassifierService} — uses field signals for classification</li>
 *   <li>{@code OcrPipelineService} — uses required fields for validation scoring</li>
 * </ul>
 */
@Slf4j
@Service
public class ExtractionTemplateService {

    private final JdbcTemplate jdbc;

    /** Cache: category_code (UPPERCASE) → list of template fields, sorted by display_order. */
    private final Map<String, List<TemplateField>> templateCache = new ConcurrentHashMap<>();

    /**
     * A single field definition in an extraction template.
     *
     * @param fieldName    canonical field name (e.g. "full_name", "invoice_total")
     * @param fieldType    data type hint ("string", "date", "number", "currency")
     * @param required     whether this field is required for a complete extraction
     * @param displayOrder ordering for UI display and prompt building
     * @param description  human-readable description of the field
     */
    public record TemplateField(String fieldName, String fieldType, boolean required,
                                 int displayOrder, String description) {}

    public ExtractionTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        refreshTemplates();
    }

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void refreshTemplates() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT category_code, field_name, field_type, required, display_order, description " +
                    "FROM ecm_admin.extraction_templates ORDER BY category_code, display_order");

            Map<String, List<TemplateField>> newCache = new HashMap<>();

            for (Map<String, Object> row : rows) {
                String catCode = row.get("category_code") != null
                        ? row.get("category_code").toString().toUpperCase() : null;
                if (catCode == null) continue;

                String fieldName = row.get("field_name") != null
                        ? row.get("field_name").toString().trim() : null;
                if (fieldName == null) continue;

                String fieldType = row.get("field_type") != null
                        ? row.get("field_type").toString().trim() : "string";
                boolean required = row.get("required") != null
                        && Boolean.parseBoolean(row.get("required").toString());
                int displayOrder = row.get("display_order") != null
                        ? Integer.parseInt(row.get("display_order").toString()) : 0;
                String description = row.get("description") != null
                        ? row.get("description").toString().trim() : null;

                newCache.computeIfAbsent(catCode, k -> new ArrayList<>())
                        .add(new TemplateField(fieldName, fieldType, required, displayOrder, description));
            }

            templateCache.clear();
            templateCache.putAll(newCache);

            log.debug("Loaded extraction templates for {} categories ({} total fields)",
                    newCache.size(), rows.size());

        } catch (Exception e) {
            log.warn("Failed to load extraction templates from DB (will use fallbacks): {}", e.getMessage());
        }
    }

    /**
     * Get all template fields for a category, sorted by display order.
     *
     * @param categoryCode category code (e.g. "IDENTITY")
     * @return list of template fields, empty if no template exists
     */
    public List<TemplateField> getFieldsForCategory(String categoryCode) {
        if (categoryCode == null) return List.of();
        return templateCache.getOrDefault(categoryCode.toUpperCase(), List.of());
    }

    /**
     * Get names of required fields for a category.
     *
     * @param categoryCode category code
     * @return list of required field names
     */
    public List<String> getRequiredFields(String categoryCode) {
        return getFieldsForCategory(categoryCode).stream()
                .filter(TemplateField::required)
                .map(TemplateField::fieldName)
                .toList();
    }

    /**
     * Get all field names for a category (required + optional).
     *
     * @param categoryCode category code
     * @return list of all field names, empty if no template exists
     */
    public List<String> getAllFieldNames(String categoryCode) {
        return getFieldsForCategory(categoryCode).stream()
                .map(TemplateField::fieldName)
                .toList();
    }

    /**
     * Calculate a field match score (0-100) based on how many template fields were extracted.
     * Required fields are weighted 2x compared to optional fields.
     *
     * @param categoryCode        category code
     * @param extractedFieldNames set of field names that were actually extracted
     * @return score 0-100 (100 = all fields extracted)
     */
    public int calculateFieldMatchScore(String categoryCode, Set<String> extractedFieldNames) {
        List<TemplateField> template = getFieldsForCategory(categoryCode);
        if (template.isEmpty() || extractedFieldNames == null || extractedFieldNames.isEmpty()) {
            return 0;
        }

        int totalWeight = 0;
        int matchedWeight = 0;

        for (TemplateField field : template) {
            int weight = field.required() ? 2 : 1;
            totalWeight += weight;
            if (extractedFieldNames.contains(field.fieldName())) {
                matchedWeight += weight;
            }
        }

        if (totalWeight == 0) return 0;
        return (int) Math.round((double) matchedWeight / totalWeight * 100.0);
    }

    /**
     * Get field signals for all categories — used by DocumentClassifierService
     * to replace hardcoded CATEGORY_FIELD_SIGNALS.
     *
     * @return map of category_code → list of field names
     */
    public Map<String, List<String>> getFieldSignals() {
        Map<String, List<String>> signals = new LinkedHashMap<>();
        for (Map.Entry<String, List<TemplateField>> entry : templateCache.entrySet()) {
            List<String> fieldNames = entry.getValue().stream()
                    .map(TemplateField::fieldName)
                    .toList();
            if (!fieldNames.isEmpty()) {
                signals.put(entry.getKey(), fieldNames);
            }
        }
        return signals;
    }

    /**
     * Check if the template cache has any entries.
     * Used by consumers to decide whether to fall back to hardcoded maps.
     */
    public boolean hasTemplates() {
        return !templateCache.isEmpty();
    }
}
