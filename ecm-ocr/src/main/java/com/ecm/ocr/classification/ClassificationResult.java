package com.ecm.ocr.classification;

import java.math.BigDecimal;

public record ClassificationResult(
        Integer categoryId,
        String categoryCode,
        BigDecimal confidence,
        String method
) {
    public static final ClassificationResult NONE =
            new ClassificationResult(null, null, BigDecimal.ZERO, "NONE");
}
