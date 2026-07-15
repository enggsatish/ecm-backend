package com.ecm.ocr.classification;

import com.ecm.common.client.AdminServiceClient;
import com.ecm.ocr.pipeline.ExtractionTemplateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Classifies a document into a category based on OCR text and extracted fields.
 * <p>
 * Uses keyword-based heuristics with category-specific dictionaries and
 * DB-driven field signals from {@link ExtractionTemplateService}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentClassifierService {

    private final AdminServiceClient adminServiceClient;
    private final ExtractionTemplateService extractionTemplateService;

    /** Well-known category keyword dictionaries (matched against OCR text) */
    private static final Map<String, List<String>> CATEGORY_KEYWORDS;
    static {
        // Using HashMap to avoid Map.of() 10-entry limit
        var kw = new HashMap<String, List<String>>();
        kw.put("IDENTITY", List.of("driver", "license", "licence", "passport", "date of birth", "expiry",
                "expires", "identification", "id card", "social security", "operator's licence",
                "operator's license", "document number", "nationality", "class:"));
        kw.put("BOARDINGPASS", List.of("boarding pass", "boarding", "flight", "departure", "arrival",
                "gate", "seat", "airline", "passenger", "terminal", "operated by"));
        kw.put("MORTGAGE", List.of("mortgage", "loan amount", "borrower", "property address",
                "interest rate", "deed of trust", "lien"));
        kw.put("FINANCIAL", List.of("statement", "balance", "account number", "transaction",
                "account summary", "deposit", "withdrawal"));
        kw.put("INVOICE", List.of("invoice", "amount due", "bill to", "payment terms",
                "billing", "total due", "remittance"));
        kw.put("LEGAL", List.of("agreement", "contract", "parties", "obligations",
                "signature", "witness", "notary", "covenant"));
        kw.put("COMPLIANCE", List.of("aml", "kyc", "compliance", "regulatory",
                "verification", "due diligence", "sanctions"));
        kw.put("RESUME", List.of("resume", "curriculum vitae", "work experience",
                "education", "skills", "objective", "references"));
        CATEGORY_KEYWORDS = Map.copyOf(kw);
    }

    /** Hardcoded fallback — used only when DB extraction_templates table is empty. */
    private static final Map<String, List<String>> CATEGORY_FIELD_SIGNALS_FALLBACK = Map.of(
            "IDENTITY", List.of("full_name", "first_name", "last_name", "document_number", "date_of_birth",
                    "expiry_date", "nationality", "sex", "address", "CountryRegion", "DateOfBirth",
                    "DocumentNumber", "FirstName", "LastName", "DateOfExpiration"),
            "BOARDINGPASS", List.of("passenger_name", "flight_number", "departure_date", "seat_number",
                    "departure_airport", "arrival_airport", "gate", "boarding_time"),
            "INVOICE", List.of("invoice_total", "invoice_date", "invoice_number", "bill_to", "ship_to",
                    "vendor_name", "amount_due", "InvoiceTotal", "InvoiceDate", "VendorName"),
            "RECEIPT", List.of("merchant_name", "transaction_date", "total", "subtotal",
                    "MerchantName", "TransactionDate", "Total"),
            "FINANCIAL", List.of("account_number", "account_balance", "statement_date", "opening_balance"),
            "TAX", List.of("tax_year", "employer", "wages", "federal_tax", "TaxYear", "Employer")
    );

    /**
     * Classify a document based on OCR text and extracted fields.
     *
     * @param ocrText         full OCR text from the document
     * @param extractedFields structured fields extracted by OCR (e.g., name, account_no)
     * @return classification result with category ID and confidence
     */
    public ClassificationResult classify(String ocrText, Map<String, String> extractedFields) {
        String text = ocrText != null ? ocrText : "";
        Map<String, String> fields = extractedFields != null ? extractedFields : Map.of();

        if (text.isBlank() && fields.isEmpty()) {
            log.warn("No OCR text or fields available for classification");
            return new ClassificationResult(null, null, BigDecimal.ZERO, "NONE");
        }

        String textLower = text.toLowerCase();

        log.debug("Classifying document: textLength={}, fieldCount={}, textPreview='{}'",
                text.length(), fields.size(),
                text.length() > 200 ? text.substring(0, 200) + "..." : text);

        // Load categories from ecm-admin to get category IDs and codes
        List<Map<String, Object>> categories = adminServiceClient.getCategories();
        log.debug("Loaded {} categories from admin service", categories.size());
        Map<String, Integer> codeToId = new HashMap<>();
        for (Map<String, Object> cat : categories) {
            String code = cat.get("code") != null ? cat.get("code").toString().toUpperCase() : null;
            Object idObj = cat.get("id");
            if (code != null && idObj != null) {
                codeToId.put(code, idObj instanceof Integer ? (Integer) idObj : Integer.parseInt(idObj.toString()));
            }
        }

        // Score each category using DB keywords (with hardcoded fallback)
        String bestCode = null;
        int bestScore = 0;
        Integer bestCategoryId = null;

        for (Map<String, Object> cat : categories) {
            String code = cat.get("code") != null ? cat.get("code").toString().toUpperCase() : null;
            if (code == null) continue;

            // Resolve keywords: DB first, hardcoded fallback
            List<String> keywords = resolveKeywords(cat, code);
            if (keywords.isEmpty()) {
                // No keywords at all — try simple code/name substring match
                String name = cat.get("name") != null ? cat.get("name").toString().toLowerCase() : null;
                int matches = 0;
                if (textLower.contains(code.toLowerCase())) matches += 2;
                if (name != null && textLower.contains(name)) matches++;
                if (matches > bestScore) {
                    bestScore = matches;
                    bestCode = code;
                    bestCategoryId = codeToId.get(code);
                }
                continue;
            }

            int matches = 0;
            List<String> matched = new ArrayList<>();
            for (String keyword : keywords) {
                if (textLower.contains(keyword.toLowerCase())) {
                    matches++;
                    matched.add(keyword);
                }
            }
            if (matches > 0) {
                log.debug("Keyword matches for {}: {} — {}", code, matches, matched);
            }
            if (matches > bestScore) {
                bestScore = matches;
                bestCode = code;
                bestCategoryId = codeToId.get(code);
            }
        }

        // Field-signal scoring: if extracted field NAMES match known category signals,
        // this is a strong indicator (e.g., Azure returned full_name + document_number → IDENTITY)
        if (!fields.isEmpty()) {
            Set<String> fieldNames = fields.keySet();
            Map<String, List<String>> fieldSignals = resolveFieldSignals();
            for (Map.Entry<String, List<String>> entry : fieldSignals.entrySet()) {
                String code = entry.getKey();
                int fieldMatches = 0;
                for (String signal : entry.getValue()) {
                    if (fieldNames.contains(signal)) fieldMatches++;
                }
                int effectiveScore = fieldMatches * 2;
                if (effectiveScore > bestScore && codeToId.containsKey(code)) {
                    bestScore = effectiveScore;
                    bestCode = code;
                    bestCategoryId = codeToId.get(code);
                    log.info("Field-signal classification: {} field names matched category {} (score={})",
                            fieldMatches, code, effectiveScore);
                }
            }
        }

        if (bestScore == 0) {
            log.debug("No classification match found for document text or fields");
            return new ClassificationResult(null, null, BigDecimal.ZERO, "NONE");
        }

        // Confidence scoring: 1 match=40%, 2=60%, 3=75%, 4+=85%
        double baseConfidence;
        if (bestScore >= 4) baseConfidence = 85.0;
        else if (bestScore == 3) baseConfidence = 75.0;
        else if (bestScore == 2) baseConfidence = 60.0;
        else baseConfidence = 40.0;

        // Boost confidence if extractedFields match known template fields for the category
        if (!fields.isEmpty() && hasTemplateFieldMatch(bestCode, fields)) {
            baseConfidence = Math.min(baseConfidence + 10.0, 99.0);
            log.debug("Boosted confidence by 10% due to extracted field template match for {}", bestCode);
        }

        BigDecimal confidence = BigDecimal.valueOf(baseConfidence).setScale(2, java.math.RoundingMode.HALF_UP);
        log.info("Classified document as {} (id={}) with confidence {}% ({} keyword matches)",
                bestCode, bestCategoryId, confidence, bestScore);

        return new ClassificationResult(bestCategoryId, bestCode, confidence, "KEYWORD");
    }

    /**
     * Resolves keywords for a category: DB-configured first, hardcoded fallback.
     */
    @SuppressWarnings("unchecked")
    private List<String> resolveKeywords(Map<String, Object> category, String code) {
        // Check DB keywords (from classification_keywords column)
        Object dbKeywords = category.get("classificationKeywords");
        if (dbKeywords instanceof List<?> list && !list.isEmpty()) {
            return (List<String>) list;
        }
        if (dbKeywords instanceof String[] arr && arr.length > 0) {
            return List.of(arr);
        }
        // Fallback to hardcoded defaults
        return CATEGORY_KEYWORDS.getOrDefault(code, List.of());
    }

    /**
     * Resolve field signals: DB-driven first, hardcoded fallback if DB is empty.
     */
    private Map<String, List<String>> resolveFieldSignals() {
        Map<String, List<String>> dbSignals = extractionTemplateService.getFieldSignals();
        if (!dbSignals.isEmpty()) return dbSignals;
        return CATEGORY_FIELD_SIGNALS_FALLBACK;
    }

    /**
     * Check if any extracted field name appears in the category's template fields.
     * DB-driven via ExtractionTemplateService, with hardcoded fallback.
     */
    private boolean hasTemplateFieldMatch(String categoryCode, Map<String, String> extractedFields) {
        if (categoryCode == null || extractedFields.isEmpty()) return false;

        // DB-driven check: if any extracted field name appears in the category's template
        List<String> templateFields = extractionTemplateService.getAllFieldNames(categoryCode);
        if (!templateFields.isEmpty()) {
            Set<String> fieldKeys = new HashSet<>();
            for (String key : extractedFields.keySet()) {
                fieldKeys.add(key.toLowerCase());
            }
            Set<String> templateLower = new HashSet<>();
            for (String tf : templateFields) {
                templateLower.add(tf.toLowerCase());
            }
            for (String key : fieldKeys) {
                if (templateLower.contains(key)) return true;
            }
            return false;
        }

        // Hardcoded fallback (original switch-based logic)
        Set<String> fieldKeys = new HashSet<>();
        for (String key : extractedFields.keySet()) {
            fieldKeys.add(key.toLowerCase());
        }

        return switch (categoryCode) {
            case "IDENTITY" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("name") || k.contains("dob") || k.contains("expiry") || k.contains("id_number"));
            case "MORTGAGE" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("loan") || k.contains("property") || k.contains("borrower") || k.contains("rate"));
            case "FINANCIAL" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("account") || k.contains("balance") || k.contains("transaction"));
            case "INVOICE" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("invoice") || k.contains("amount") || k.contains("vendor"));
            case "LEGAL" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("party") || k.contains("date") || k.contains("signature"));
            case "COMPLIANCE" -> fieldKeys.stream().anyMatch(k ->
                    k.contains("kyc") || k.contains("aml") || k.contains("risk"));
            default -> false;
        };
    }
}
