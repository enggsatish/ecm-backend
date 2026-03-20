package com.ecm.ocr.pipeline;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FieldExtractorService {

    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    // Keyed by categoryCode (upper-case) → template
    private final Map<String, ExtractionTemplate> templates = new HashMap<>();

    /**
     * Loads extraction templates on startup.
     *
     * Primary source: ecm_admin.ocr_templates table (DB-stored, admin-managed).
     * Fallback: classpath JSON files (for dev/bootstrap when DB is empty).
     */
    @PostConstruct
    public void loadTemplates() {
        int dbLoaded = loadFromDatabase();
        if (dbLoaded > 0) {
            log.info("Loaded {} OCR extraction template(s) from database", dbLoaded);
            return;
        }

        log.info("No templates in database — falling back to classpath JSON files");
        loadFromClasspath();
    }

    /**
     * Reloads templates from the database. Call this after admin creates/updates templates.
     */
    public void refreshTemplates() {
        templates.clear();
        int loaded = loadFromDatabase();
        log.info("OCR templates refreshed: {} loaded from database", loaded);
        if (loaded == 0) {
            loadFromClasspath();
        }
    }

    private int loadFromDatabase() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT category_code, name, description, fields " +
                    "FROM ecm_admin.ocr_templates WHERE is_active = true");

            int loaded = 0;
            for (Map<String, Object> row : rows) {
                try {
                    String categoryCode = (String) row.get("category_code");
                    String fieldsJson = row.get("fields") != null ? row.get("fields").toString() : "[]";

                    List<ExtractionTemplate.FieldPattern> fields = objectMapper.readValue(
                            fieldsJson, new TypeReference<>() {});

                    ExtractionTemplate template = new ExtractionTemplate(
                            categoryCode,
                            (String) row.get("description"),
                            fields);

                    templates.put(categoryCode.toUpperCase(), template);
                    loaded++;
                } catch (Exception e) {
                    log.error("Failed to parse OCR template for category '{}': {}",
                            row.get("category_code"), e.getMessage());
                }
            }
            return loaded;
        } catch (Exception e) {
            log.warn("Could not load OCR templates from database (table may not exist yet): {}",
                    e.getMessage());
            return 0;
        }
    }

    private void loadFromClasspath() {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath:ocr/extraction-templates/*.json");
        } catch (IOException e) {
            log.warn("Could not scan extraction-templates directory: {} — field extraction disabled",
                    e.getMessage());
            return;
        }

        int loaded = 0;
        int failed = 0;
        for (Resource r : resources) {
            try {
                ExtractionTemplate t = objectMapper.readValue(r.getInputStream(),
                        ExtractionTemplate.class);
                templates.put(t.categoryCode().toUpperCase(), t);
                log.info("Loaded extraction template from classpath: {} ({} fields)",
                        t.categoryCode(), t.fields().size());
                loaded++;
            } catch (Exception e) {
                log.error("Skipping malformed template '{}': {}",
                        r.getFilename(), e.getMessage());
                failed++;
            }
        }
        log.info("Classpath extraction templates: {} loaded, {} failed to parse", loaded, failed);
    }

    /**
     * Applies the template for the given categoryCode against extractedText.
     * Returns an empty map if no template exists for the category.
     */
    public Map<String, Object> extract(String categoryCode, String extractedText) {
        if (categoryCode == null) {
            log.info("Field extraction skipped — categoryCode is null");
            return Collections.emptyMap();
        }
        if (extractedText == null || extractedText.isBlank()) {
            log.info("Field extraction skipped — extractedText is blank for categoryCode={}",
                    categoryCode);
            return Collections.emptyMap();
        }

        ExtractionTemplate template = templates.get(categoryCode.toUpperCase());
        if (template == null) {
            log.info("No extraction template for categoryCode={}", categoryCode);
            return Collections.emptyMap();
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        int matched = 0;
        for (ExtractionTemplate.FieldPattern fp : template.fields()) {
            try {
                Pattern p = Pattern.compile(fp.pattern(),
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher m = p.matcher(extractedText);
                if (m.find() && m.groupCount() >= 1) {
                    fields.put(fp.fieldName(), m.group(1).strip());
                    matched++;
                } else if (fp.defaultValue() != null) {
                    fields.put(fp.fieldName(), fp.defaultValue());
                }
            } catch (PatternSyntaxException e) {
                log.warn("Invalid pattern for field {}: {}", fp.fieldName(), e.getMessage());
            }
        }
        log.info("Field extraction complete: category={}, template_fields={}, regex_matched={}, text_chars={}",
                categoryCode, template.fields().size(), matched, extractedText.length());
        return fields;
    }

    public boolean hasTemplate(String categoryCode) {
        return categoryCode != null && templates.containsKey(categoryCode.toUpperCase());
    }
}
