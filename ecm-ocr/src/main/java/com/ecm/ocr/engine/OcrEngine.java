package com.ecm.ocr.engine;

import java.io.InputStream;

/**
 * Extracts text from a document byte stream.
 *
 * Single active implementation: {@link TikaOcrEngine}.
 * A second implementation (e.g. Google Document AI) would be added here
 * if the platform ever needs an alternative extraction path.
 */
public interface OcrEngine {

    /**
     * Extracts text from the given stream.
     *
     * @param stream      raw document bytes — must not be null
     * @param contentType MIME type (e.g. "application/pdf", "image/jpeg") —
     *                    used to choose the extraction path
     * @return extracted plain text; empty string if no text was found
     * @throws OcrException on unrecoverable extraction failure
     */
    String extract(InputStream stream, String contentType) throws OcrException;

    /**
     * Extracts text with documentId available for log correlation.
     *
     * Default implementation delegates to {@link #extract(InputStream, String)}.
     * Implementations that use the ID for logging (e.g. TikaOcrEngine) should
     * override this method.
     *
     * @param stream      raw document bytes
     * @param contentType MIME type
     * @param documentId  document identifier for log tracing (UUID or Integer)
     * @return extracted plain text; empty string if no text was found
     */
    default String extract(InputStream stream, String contentType,
                           Object documentId) throws OcrException {
        return extract(stream, contentType);
    }

    /**
     * Thrown when text extraction fails in a way that cannot be recovered from.
     * OcrPipelineService catches this and routes the message to the DLQ.
     */
    class OcrException extends RuntimeException {
        public OcrException(String msg, Throwable cause) { super(msg, cause); }
    }
}
