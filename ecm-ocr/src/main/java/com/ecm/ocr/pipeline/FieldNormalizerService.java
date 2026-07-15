package com.ecm.ocr.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Normalizes OCR-extracted field names to canonical names using DB-driven mappings.
 *
 * <p>Field mappings are loaded from {@code ecm_admin.field_mappings} and cached in memory.
 * The cache refreshes every 5 minutes via {@code @Scheduled}.</p>
 *
 * <h3>Normalization pipeline:</h3>
 * <ol>
 *   <li>Map raw field names to canonical names using the mapping table</li>
 *   <li>If no mapping exists, apply default normalization (lowercase + underscore)</li>
 *   <li>Coalesce duplicates — keep first non-blank value</li>
 *   <li>Synthesize {@code full_name} from first_name + middle_name + last_name if missing</li>
 * </ol>
 */
@Slf4j
@Service
public class FieldNormalizerService {

    private final JdbcTemplate jdbc;

    /**
     * Mapping key: "CATEGORY_CODE:raw_name" (both lowercased) → canonical_name.
     * Global mappings (no category) use key ":raw_name".
     */
    private final Map<String, String> mappingCache = new ConcurrentHashMap<>();

    /** Field type hints from mapping table: canonical_name → field_type (e.g. "date", "number"). */
    private final Map<String, String> fieldTypeCache = new ConcurrentHashMap<>();

    /** Well-known alias mappings used when DB has no entry (always applied). */
    private static final Map<String, String> DEFAULT_ALIASES = Map.ofEntries(
            Map.entry("given_names", "first_name"),
            Map.entry("given_name", "first_name"),
            Map.entry("firstname", "first_name"),
            Map.entry("first name", "first_name"),
            Map.entry("surname", "last_name"),
            Map.entry("family_name", "last_name"),
            Map.entry("lastname", "last_name"),
            Map.entry("last name", "last_name"),
            Map.entry("middle_names", "middle_name"),
            Map.entry("middlename", "middle_name"),
            Map.entry("dob", "date_of_birth"),
            Map.entry("dateofbirth", "date_of_birth"),
            Map.entry("date_of_birth", "date_of_birth"),
            Map.entry("expiry", "expiry_date"),
            Map.entry("dateofexpiration", "expiry_date"),
            Map.entry("id_number", "document_number"),
            Map.entry("documentnumber", "document_number"),
            Map.entry("countryregion", "nationality"),
            Map.entry("invoicetotal", "invoice_total"),
            Map.entry("invoicedate", "invoice_date"),
            Map.entry("vendorname", "vendor_name"),
            Map.entry("merchantname", "merchant_name"),
            Map.entry("transactiondate", "transaction_date"),
            Map.entry("taxyear", "tax_year")
    );

    public FieldNormalizerService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        refreshMappings();
    }

    @Scheduled(fixedRate = 300_000) // every 5 minutes
    public void refreshMappings() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT category_code, raw_name, canonical_name, field_type FROM ecm_admin.field_mappings");

            Map<String, String> newMappings = new HashMap<>();
            Map<String, String> newTypes = new HashMap<>();

            for (Map<String, Object> row : rows) {
                String catCode = row.get("category_code") != null
                        ? row.get("category_code").toString().toUpperCase() : "";
                String rawName = row.get("raw_name") != null
                        ? row.get("raw_name").toString().toLowerCase().trim() : null;
                String canonical = row.get("canonical_name") != null
                        ? row.get("canonical_name").toString().trim() : null;
                String fieldType = row.get("field_type") != null
                        ? row.get("field_type").toString().trim() : null;

                if (rawName == null || canonical == null) continue;

                String key = catCode + ":" + rawName;
                newMappings.put(key, canonical);

                if (fieldType != null && !fieldType.isBlank()) {
                    newTypes.put(canonical, fieldType);
                }
            }

            mappingCache.clear();
            mappingCache.putAll(newMappings);
            fieldTypeCache.clear();
            fieldTypeCache.putAll(newTypes);

            log.debug("Loaded {} field mappings, {} field types from ecm_admin.field_mappings",
                    newMappings.size(), newTypes.size());

        } catch (Exception e) {
            log.warn("Failed to load field mappings from DB (will use defaults): {}", e.getMessage());
        }
    }

    /**
     * Normalize extracted fields using DB mappings + default aliases.
     *
     * @param categoryCode document category (e.g. "IDENTITY"), may be null
     * @param fields       raw extracted fields from OCR engine
     * @return normalized field map with canonical names
     */
    public Map<String, Object> normalize(String categoryCode, Map<String, Object> fields) {
        if (fields == null || fields.isEmpty()) return Map.of();

        String catUpper = categoryCode != null ? categoryCode.toUpperCase() : "";
        Map<String, Object> normalized = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String rawKey = entry.getKey();
            Object value = entry.getValue();

            // Skip internal fields (prefixed with _)
            if (rawKey.startsWith("_")) {
                normalized.put(rawKey, value);
                continue;
            }

            // Resolve canonical name: DB category-specific → DB global → default alias → raw normalized
            String canonical = resolveCanonicalName(catUpper, rawKey);

            // Coalesce duplicates — keep first non-blank value
            if (normalized.containsKey(canonical)) {
                Object existing = normalized.get(canonical);
                if (isBlankValue(existing) && !isBlankValue(value)) {
                    normalized.put(canonical, value);
                }
                // For name fields, keep the longer value (more complete)
                if (canonical.contains("name") && !isBlankValue(existing) && !isBlankValue(value)) {
                    if (value.toString().trim().length() > existing.toString().trim().length()) {
                        normalized.put(canonical, value);
                    }
                }
            } else {
                normalized.put(canonical, value);
            }
        }

        // Synthesize full_name from parts if missing
        synthesizeFullName(normalized);

        return normalized;
    }

    /**
     * Normalize a raw field name to lowercase_underscore format.
     * Strips non-alphanumeric chars (except underscores), collapses multiple underscores.
     */
    public static String normalizeFieldName(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        return raw.toLowerCase()
                .replace(" ", "_")
                .replaceAll("[^a-z0-9_]", "")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    // ── Internal helpers ────────────────────────────────────────────────────────

    private String resolveCanonicalName(String catUpper, String rawKey) {
        String rawLower = rawKey.toLowerCase().trim();

        // 1. Category-specific DB mapping
        String catKey = catUpper + ":" + rawLower;
        String canonical = mappingCache.get(catKey);
        if (canonical != null) return canonical;

        // 2. Global DB mapping (no category)
        String globalKey = ":" + rawLower;
        canonical = mappingCache.get(globalKey);
        if (canonical != null) return canonical;

        // 3. Default alias
        String normalizedRaw = normalizeFieldName(rawKey);
        canonical = DEFAULT_ALIASES.get(normalizedRaw);
        if (canonical != null) return canonical;

        // Also try the original lowercase (before stripping special chars)
        canonical = DEFAULT_ALIASES.get(rawLower);
        if (canonical != null) return canonical;

        // 4. Fall back to normalized raw name
        return normalizedRaw;
    }

    private void synthesizeFullName(Map<String, Object> fields) {
        if (fields.containsKey("full_name") && !isBlankValue(fields.get("full_name"))) {
            return;
        }

        String first = strVal(fields, "first_name");
        String middle = strVal(fields, "middle_name");
        String last = strVal(fields, "last_name");

        if (first == null && last == null) return;

        var sb = new StringBuilder();
        if (first != null) sb.append(first);
        if (middle != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(middle);
        }
        if (last != null) {
            if (!sb.isEmpty()) sb.append(" ");
            sb.append(last);
        }

        if (!sb.isEmpty()) {
            fields.put("full_name", sb.toString());
            log.debug("Synthesized full_name: {}", sb);
        }
    }

    private static String strVal(Map<String, Object> fields, String key) {
        Object v = fields.get(key);
        return v != null && !v.toString().isBlank() ? v.toString().trim() : null;
    }

    private static boolean isBlankValue(Object value) {
        return value == null || value.toString().isBlank();
    }
}
