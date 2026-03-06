package com.ecm.ocr.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ecm.ocr")
public class OcrProperties {
    /** Max file size in bytes; larger files skip OCR and are indexed by metadata only */
    private long maxFileSizeBytes = 52_428_800L;
    /** Enable Tesseract for image-heavy PDFs and scanned images */
    private boolean tesseractEnabled = true;
    /** Tesseract language pack codes, comma-separated */
    private String tesseractLanguages = "eng";
}
