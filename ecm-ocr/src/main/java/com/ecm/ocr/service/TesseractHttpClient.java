package com.ecm.ocr.service;

import com.ecm.ocr.properties.OcrProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * HTTP client for the Tesseract Docker container (hertzg/tesseract-server).
 *
 * ── hertzg/tesseract-server API (discovered from container source) ────────────
 *
 *   POST /tesseract
 *   Content-Type: multipart/form-data
 *   Fields:
 *     file     = <image bytes>   (required)
 *     options  = <JSON string>   (required — server routes on this field's presence;
 *                                 without it the route does not match → 404)
 *
 *   Options JSON shape:
 *     {
 *       "languages": ["eng"],       ← array of Tesseract language codes
 *       "configParams": {}          ← additional tesseract config (can be empty)
 *     }
 *
 *   Response: 200 application/json
 *     { "text": "extracted text here", ... }
 *   OR 200 text/plain depending on version — we handle both.
 *
 * ── Why options is required ───────────────────────────────────────────────────
 * The Deno/Express server registers its route handler with a body-parser that
 * only matches when the multipart form contains an "options" field. Without it
 * the router falls through to a 404 handler. This is the reason POST /tesseract
 * returned 404 in curl tests that only sent the "file" field.
 *
 * ── Failure contract ──────────────────────────────────────────────────────────
 * All failures are caught, logged at WARN, and return empty string.
 * A Tesseract failure never crashes the OCR pipeline — the document is indexed
 * with whatever embedded text Tika extracted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TesseractHttpClient {

    private final OcrProperties props;

    private RestClient restClient;

    @PostConstruct
    void init() {
        if (!props.isTesseractServerEnabled()) {
            log.info("TesseractHttpClient: remote server disabled (tesseract-server-enabled=false).");
            return;
        }

        int timeoutMs = props.getTesseractServerTimeoutSeconds() * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutMs);

        restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getTesseractServerUrl())
                .build();

        log.info("TesseractHttpClient initialised: url={}{}, file-field={}, timeout={}s, languages={}",
                props.getTesseractServerUrl(),
                props.getTesseractServerApiPath(),
                props.getTesseractServerFileField(),
                props.getTesseractServerTimeoutSeconds(),
                props.getTesseractLanguages());
    }

    /**
     * Sends image bytes to the Tesseract HTTP container and returns extracted text.
     *
     * @param imageBytes  raw image bytes (PNG, JPEG, TIFF, BMP)
     * @param contentType MIME type of the image
     * @param documentId  for log correlation only
     * @return extracted text, or empty string on any failure
     */
    public String recognizeImage(byte[] imageBytes, String contentType, Object documentId) {
        if (!props.isTesseractServerEnabled() || restClient == null) {
            log.debug("TesseractHttpClient skipped (server disabled): documentId={}", documentId);
            return "";
        }

        log.debug("Sending {} bytes ({}) to Tesseract server: documentId={}",
                imageBytes.length, contentType, documentId);

        try {
            // ── image file field ─────────────────────────────────────────────
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "image." + mimeToExtension(contentType);
                }
            };

            // ── options field (required by hertzg/tesseract-server) ──────────
            // The server's route only matches when this field is present.
            // Without it, the multipart body parser doesn't match the route
            // and the server falls through to a 404 handler.
            String optionsJson = buildOptionsJson(props.getTesseractLanguages());

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add(props.getTesseractServerFileField(), imageResource);
            body.add("options", optionsJson);

            String raw = restClient.post()
                    .uri(props.getTesseractServerApiPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new TesseractServerException(
                                "Tesseract server returned HTTP " + response.getStatusCode().value());
                    })
                    .body(String.class);

            String text = parseText(raw);
            log.debug("Tesseract returned {} chars: documentId={}", text.length(), documentId);
            return text;

        } catch (TesseractServerException e) {
            log.warn("Tesseract HTTP error: documentId={}, reason={}", documentId, e.getMessage());
            return "";
        } catch (RestClientException e) {
            log.warn("Tesseract server unreachable: documentId={}, url={}{}, error={}",
                    documentId,
                    props.getTesseractServerUrl(),
                    props.getTesseractServerApiPath(),
                    e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Unexpected Tesseract error: documentId={}, error={}",
                    documentId, e.getMessage(), e);
            return "";
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds the options JSON string required by hertzg/tesseract-server.
     *
     * @param languages comma-separated language codes from OcrProperties
     *                  e.g. "eng" → ["eng"],  "eng+fra" → ["eng","fra"]
     */
    private static String buildOptionsJson(String languages) {
        // Convert "eng" or "eng+fra" to JSON array ["eng"] or ["eng","fra"]
        String[] langs = languages.split("[,+]");
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < langs.length; i++) {
            if (i > 0) arr.append(",");
            arr.append("\"").append(langs[i].strip()).append("\"");
        }
        arr.append("]");
        return "{\"languages\":" + arr + ",\"configParams\":{}}";
    }

    /**
     * Parses the Tesseract server response.
     *
     * hertzg/tesseract-server returns JSON: {"text":"...","hocr":"...","tsv":"..."}
     * Older versions return plain text directly.
     * We try JSON first; fall back to treating the entire response as plain text.
     */
    private static String parseText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.strip();

        // JSON response — extract the "text" field
        if (trimmed.startsWith("{")) {
            // Simple extraction without a JSON library dependency in this method.
            // Handles: {"text":"extracted content here","hocr":...}
            int textIdx = trimmed.indexOf("\"text\"");
            if (textIdx >= 0) {
                int colonIdx = trimmed.indexOf(':', textIdx);
                if (colonIdx >= 0) {
                    int quoteStart = trimmed.indexOf('"', colonIdx + 1);
                    if (quoteStart >= 0) {
                        // Find the closing quote, respecting escaped quotes
                        int quoteEnd = quoteStart + 1;
                        while (quoteEnd < trimmed.length()) {
                            char c = trimmed.charAt(quoteEnd);
                            if (c == '"' && trimmed.charAt(quoteEnd - 1) != '\\') break;
                            quoteEnd++;
                        }
                        String extracted = trimmed.substring(quoteStart + 1, quoteEnd);
                        // Unescape JSON string: \n → newline, \" → ", \\ → \
                        return extracted
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t")
                                .replace("\\\"", "\"")
                                .replace("\\\\", "\\")
                                .strip();
                    }
                }
            }
        }

        // Plain text response (older versions or non-JSON containers)
        return trimmed;
    }

    private static String mimeToExtension(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType.toLowerCase()) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png"               -> "png";
            case "image/tiff"              -> "tiff";
            case "image/bmp"               -> "bmp";
            case "image/gif"               -> "gif";
            case "image/webp"              -> "webp";
            default                        -> "bin";
        };
    }

    private static class TesseractServerException extends RuntimeException {
        TesseractServerException(String msg) { super(msg); }
    }
}
