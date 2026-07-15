package com.ecm.ocr.engine;

import com.ecm.ocr.engine.EngineContext.FewShotExample;
import com.ecm.ocr.pipeline.ExtractionTemplateService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds structured prompts for GLM-OCR (Ollama vision model).
 *
 * <p>GLM-OCR returns structured JSON with text, regions, and bounding boxes.
 * This builder crafts prompts that guide the model to also classify the document
 * and extract named fields relevant to the detected category.</p>
 *
 * <h3>Prompt strategy:</h3>
 * <ul>
 *   <li><b>Known category:</b> "This is a {CATEGORY} document. Extract these fields: ..."</li>
 *   <li><b>Unknown category:</b> "Analyze this document. Classify into one of: ... Extract all fields."</li>
 *   <li><b>Text-only (no image):</b> "Given this OCR text, classify and extract fields."</li>
 * </ul>
 *
 * <p>Field lists are loaded from DB via {@link ExtractionTemplateService}. Hardcoded
 * fallbacks are used only when DB returns empty for a category.</p>
 *
 * <p>Few-shot examples from {@link EngineContext#fewShotExamples()} are injected
 * when available to improve accuracy over time.</p>
 */
@Component
public class GlmPromptBuilder {

    private final ExtractionTemplateService extractionTemplateService;

    /** Hardcoded fallback — used only when DB has no template for a category. */
    private static final Map<String, List<String>> CATEGORY_FIELDS_FALLBACK = Map.ofEntries(
            Map.entry("IDENTITY", List.of("full_name", "first_name", "middle_name", "last_name",
                    "given_names", "surname", "document_number",
                    "date_of_birth", "expiry_date", "nationality", "sex", "address")),
            Map.entry("BOARDINGPASS", List.of("passenger_name", "flight_number", "departure_date",
                    "departure_airport", "arrival_airport", "seat_number", "gate", "boarding_time")),
            Map.entry("INVOICE", List.of("invoice_number", "invoice_date", "vendor_name", "bill_to",
                    "amount_due", "invoice_total", "payment_terms")),
            Map.entry("RECEIPT", List.of("merchant_name", "transaction_date", "total", "subtotal",
                    "payment_method")),
            Map.entry("MORTGAGE", List.of("borrower_name", "loan_amount", "interest_rate",
                    "property_address", "loan_term", "lender_name")),
            Map.entry("FINANCIAL", List.of("account_number", "account_holder", "statement_date",
                    "opening_balance", "closing_balance")),
            Map.entry("LEGAL", List.of("party_names", "agreement_date", "effective_date",
                    "document_title", "jurisdiction")),
            Map.entry("COMPLIANCE", List.of("subject_name", "verification_date", "risk_level",
                    "verifier", "compliance_type")),
            Map.entry("RESUME", List.of("candidate_name", "email", "phone", "current_role",
                    "years_experience")),
            Map.entry("TAX", List.of("tax_year", "employer_name", "wages", "federal_tax_withheld",
                    "taxpayer_name"))
    );

    public GlmPromptBuilder(ExtractionTemplateService extractionTemplateService) {
        this.extractionTemplateService = extractionTemplateService;
    }

    /**
     * Build a prompt for document analysis with image.
     * Used when GLM-OCR processes the actual document image.
     */
    public String buildImagePrompt(EngineContext ctx) {
        var sb = new StringBuilder();

        if (ctx.categoryCode() != null) {
            // Known category — targeted extraction
            sb.append("This is a ").append(ctx.categoryCode()).append(" document.\n");
            sb.append("Extract all visible text from this document image.\n");
            sb.append("Also extract these structured fields: ");
            List<String> fields = getFieldsForCategory(ctx.categoryCode());
            sb.append(fields.isEmpty() ? "any identifiable fields" : String.join(", ", fields));
            sb.append(".\n\n");
        } else {
            // Unknown category — classify + extract
            sb.append("Analyze this document image.\n");
            sb.append("1. Extract all visible text.\n");
            sb.append("2. Classify the document into ONE of these categories: ");
            sb.append(getAllCategoryNames()).append("\n");
            sb.append("3. Extract any structured fields you can identify (names, dates, numbers, addresses).\n");
            sb.append("4. Rate your classification confidence from 0 to 100.\n\n");
        }

        // Add few-shot examples if available
        appendFewShotExamples(sb, ctx.fewShotExamples(), ctx.categoryCode());

        // Output format instruction — concrete example prevents GLM-OCR from echoing template
        sb.append("IMPORTANT: Return ONLY raw JSON, no markdown fences, no ```json blocks, no explanation.\n");
        sb.append("Use this exact format:\n");
        if (ctx.categoryCode() == null) {
            sb.append("{\"text\":\"all visible text here\",\"category\":\"IDENTITY\",\"confidence\":85,");
            sb.append("\"fields\":{\"full_name\":\"Mary Ann Jane Smith\",\"first_name\":\"Mary\",\"middle_name\":\"Ann Jane\",\"last_name\":\"Smith\",");
            sb.append("\"document_number\":\"A1234567\",\"date_of_birth\":\"1990-01-15\",\"expiry_date\":\"2028-06-30\"}}\n");
        } else {
            sb.append("{\"text\":\"all visible text here\",");
            sb.append("\"fields\":{\"full_name\":\"Mary Ann Jane Smith\",\"first_name\":\"Mary\",\"middle_name\":\"Ann Jane\",\"last_name\":\"Smith\",");
            sb.append("\"document_number\":\"A1234567\",\"date_of_birth\":\"1990-01-15\"}}\n");
        }
        sb.append("Replace the example values with actual data from the document. Extract ALL visible fields, not just these examples.\n");
        appendNameAndFieldGuardrails(sb);

        return sb.toString();
    }

    /**
     * Build a prompt for text-based classification and extraction.
     * Used when a prior engine (RapidOCR) already extracted text and
     * GLM-OCR should classify/extract from that text without re-reading the image.
     */
    public String buildTextPrompt(EngineContext ctx) {
        var sb = new StringBuilder();

        sb.append("Given the following OCR text extracted from a document, ");

        if (ctx.categoryCode() != null) {
            sb.append("this is a ").append(ctx.categoryCode()).append(" document.\n");
            sb.append("Extract these structured fields: ");
            List<String> fields = getFieldsForCategory(ctx.categoryCode());
            sb.append(fields.isEmpty() ? "any identifiable fields" : String.join(", ", fields));
            sb.append(".\n\n");
        } else {
            sb.append("perform the following:\n");
            sb.append("1. Classify the document into ONE of: ").append(getAllCategoryNames()).append("\n");
            sb.append("2. Extract any structured fields (names, dates, numbers, addresses).\n");
            sb.append("3. Rate your classification confidence from 0 to 100.\n\n");
        }

        // Include the OCR text
        sb.append("--- OCR TEXT ---\n");
        String text = ctx.previousText();
        // Limit text to avoid exceeding context window on 16GB system
        if (text.length() > 6000) {
            sb.append(text, 0, 6000).append("\n[... truncated ...]\n");
        } else {
            sb.append(text).append("\n");
        }
        sb.append("--- END OCR TEXT ---\n\n");

        appendFewShotExamples(sb, ctx.fewShotExamples(), ctx.categoryCode());

        sb.append("IMPORTANT: Return ONLY raw JSON, no markdown fences, no ```json blocks, no explanation.\n");
        sb.append("Use this exact format:\n");
        if (ctx.categoryCode() == null) {
            sb.append("{\"category\":\"IDENTITY\",\"confidence\":85,");
            sb.append("\"fields\":{\"full_name\":\"Mary Ann Jane Smith\",\"first_name\":\"Mary\",\"middle_name\":\"Ann Jane\",\"last_name\":\"Smith\",");
            sb.append("\"document_number\":\"A1234567\",\"date_of_birth\":\"1990-01-15\",\"expiry_date\":\"2028-06-30\"}}\n");
        } else {
            sb.append("{\"fields\":{\"full_name\":\"Mary Ann Jane Smith\",\"first_name\":\"Mary\",\"middle_name\":\"Ann Jane\",\"last_name\":\"Smith\",");
            sb.append("\"document_number\":\"A1234567\",\"date_of_birth\":\"1990-01-15\"}}\n");
        }
        sb.append("Replace the example values with actual data from the OCR text. Extract ALL identifiable fields, not just these examples.\n");
        appendNameAndFieldGuardrails(sb);

        return sb.toString();
    }

    /**
     * Shared guardrail instructions appended after the output-format example.
     * Addresses two observed LLM failure modes: truncating multi-part names to
     * two tokens, and inventing fields for icons/indicators (e.g. an organ-donor
     * marker) that were never requested.
     */
    private void appendNameAndFieldGuardrails(StringBuilder sb) {
        sb.append("Names often include multiple given/middle names (e.g. \"ANDERSON, ERIN AMANDA JANE\") — ");
        sb.append("include EVERY given/middle name present, never drop or shorten to just one.\n");
        sb.append("Only return fields from the requested list above (plus full_name/first_name/middle_name/last_name) — ");
        sb.append("do not invent fields for icons, symbols, or indicators (e.g. an organ donor marker) that were not requested.\n");
    }

    /**
     * Get field names for a category — DB first, hardcoded fallback.
     * Public so engines can filter LLM output to only the fields actually requested
     * (guards against hallucinated fields like a "donor name" invented from an icon).
     */
    public List<String> getFieldsForCategory(String categoryCode) {
        if (categoryCode == null) return List.of();
        List<String> dbFields = extractionTemplateService.getAllFieldNames(categoryCode);
        if (!dbFields.isEmpty()) return dbFields;
        return CATEGORY_FIELDS_FALLBACK.getOrDefault(categoryCode.toUpperCase(), List.of());
    }

    /**
     * Get all known category names for classification prompts.
     * DB-driven categories first, fallback keys appended if DB is empty.
     */
    private String getAllCategoryNames() {
        Map<String, List<String>> signals = extractionTemplateService.getFieldSignals();
        if (!signals.isEmpty()) {
            return String.join(", ", signals.keySet());
        }
        return String.join(", ", CATEGORY_FIELDS_FALLBACK.keySet());
    }

    private void appendFewShotExamples(StringBuilder sb, List<FewShotExample> examples, String categoryCode) {
        if (examples == null || examples.isEmpty()) return;

        // Filter to relevant category if known, limit to 2 examples to save context
        List<FewShotExample> relevant = examples.stream()
                .filter(e -> categoryCode == null || categoryCode.equalsIgnoreCase(e.categoryCode()))
                .limit(2)
                .toList();

        if (relevant.isEmpty()) return;

        sb.append("Here are examples of expected output for similar documents:\n\n");
        for (int i = 0; i < relevant.size(); i++) {
            sb.append("Example ").append(i + 1).append(" (").append(relevant.get(i).categoryCode()).append("):\n");
            sb.append(relevant.get(i).expectedJson()).append("\n\n");
        }
    }
}
