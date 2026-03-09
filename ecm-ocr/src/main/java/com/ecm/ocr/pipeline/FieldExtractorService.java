package com.ecm.ocr.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FieldExtractorService {

    private final ObjectMapper objectMapper;

    // Keyed by categoryCode (upper-case) → template
    private final Map<String, ExtractionTemplate> templates = new HashMap<>();

    /**
     * Loads all extraction templates from classpath:ocr/extraction-templates/*.json.
     *
     * Per-file error handling: a malformed or truncated JSON file is logged and skipped
     * — it does NOT crash the application context. A startup failure here would block
     * ALL OCR processing for every document type, which is far worse than one bad template.
     *
     * If a template fails to load, OCR still runs; the affected category returns no
     * structured fields (raw extracted text is still stored). Fix the JSON and restart.
     *
     * Common cause: file truncated by a zip extraction issue, saved mid-edit,
     * or hand-edited with a missing closing bracket/quote.
     */
    @PostConstruct
    public void loadTemplates() {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();

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
                log.info("Loaded extraction template: {} ({} fields)",
                        t.categoryCode(), t.fields().size());
                loaded++;
            } catch (Exception e) {
                // Log the exact filename and parse error so it is easy to find and fix.
                // JsonEOFException means the file is truncated (incomplete JSON).
                log.error("Skipping malformed template '{}': {} " +
                                "— fix the file and restart to reload.",
                        r.getFilename(), e.getMessage());
                failed++;
            }
        }
        log.info("Extraction templates: {} loaded, {} failed to parse", loaded, failed);
        if (failed > 0) {
            log.warn("{} template(s) could not be loaded. " +
                            "Those document categories will produce no structured fields. " +
                            "Check files under src/main/resources/ocr/extraction-templates/",
                    failed);
        }
    }

    /**
     * Applies the template for the given categoryCode against extractedText.
     * Returns an empty map if no template exists for the category.
     */
    public Map<String, Object> extract(String categoryCode, String extractedText) {
        if (categoryCode == null || extractedText == null || extractedText.isBlank())
            return Collections.emptyMap();

        ExtractionTemplate template = templates.get(categoryCode.toUpperCase());
        if (template == null) {
            log.debug("No extraction template for categoryCode={}", categoryCode);
            return Collections.emptyMap();
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (ExtractionTemplate.FieldPattern fp : template.fields()) {
            try {
                Pattern p = Pattern.compile(fp.pattern(),
                        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher m = p.matcher(extractedText);
                if (m.find() && m.groupCount() >= 1) {
                    fields.put(fp.fieldName(), m.group(1).strip());
                } else if (fp.defaultValue() != null) {
                    fields.put(fp.fieldName(), fp.defaultValue());
                }
            } catch (PatternSyntaxException e) {
                log.warn("Invalid pattern for field {}: {}", fp.fieldName(), e.getMessage());
            }
        }
        log.debug("Extracted {} fields for category={}", fields.size(), categoryCode);
        return fields;
    }

    public boolean hasTemplate(String categoryCode) {
        return categoryCode != null && templates.containsKey(categoryCode.toUpperCase());
    }
}