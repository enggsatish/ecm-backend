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

    @PostConstruct
    public void loadTemplates() throws IOException {
        PathMatchingResourcePatternResolver resolver =
                new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(
                "classpath:ocr/extraction-templates/*.json");

        for (Resource r : resources) {
            ExtractionTemplate t = objectMapper.readValue(r.getInputStream(),
                    ExtractionTemplate.class);
            templates.put(t.categoryCode().toUpperCase(), t);
            log.info("Loaded extraction template: {} ({} fields)",
                    t.categoryCode(), t.fields().size());
        }
        log.info("Total extraction templates loaded: {}", templates.size());
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
