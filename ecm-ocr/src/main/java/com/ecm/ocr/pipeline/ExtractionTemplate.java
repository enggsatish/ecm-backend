package com.ecm.ocr.pipeline;

import java.util.List;

/**
 * JSON-serializable template that describes named regex patterns to apply
 * against extracted text for a specific document category.
 *
 * Example: MORTGAGE.json defines patterns for loan_amount, borrower_name, etc.
 * Applied by FieldExtractorService after Tika extraction completes.
 */
public record ExtractionTemplate(
        String       categoryCode,
        String       description,
        List<FieldPattern> fields
) {
    public record FieldPattern(
            String fieldName,
            String pattern,       // Java regex; group(1) is the captured value
            String defaultValue   // returned when pattern does not match
    ) {}
}
