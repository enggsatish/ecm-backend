package com.ecm.ocr.engine;

import java.io.InputStream;

/**
 * Extracts text from a document byte stream.
 * Single implementation (Tika) — no abstraction needed unless
 * a second engine (e.g. Google Document AI) is added later.
 */
public interface OcrEngine {
    /**
     * @param stream      raw document bytes
     * @param contentType MIME type (e.g. "application/pdf", "image/jpeg")
     * @return extracted plain text; empty string if no text found
     */
    String extract(InputStream stream, String contentType) throws OcrException;

    class OcrException extends RuntimeException {
        public OcrException(String msg, Throwable cause) { super(msg, cause); }
    }
}
