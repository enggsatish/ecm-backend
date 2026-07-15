package com.ecm.ocr.service;

import com.ecm.ocr.properties.OcrProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * HTTP client for RapidOCR (and compatible) container servers.
 *
 * <p>Supports multiple response formats:</p>
 * <ul>
 *   <li><b>RapidOCR</b>: {@code {"0":{"rec_txt":"..."},"1":{"rec_txt":"..."}}}</li>
 *   <li><b>PaddleOCR wrappers</b>: {@code {"text":"..."}}</li>
 *   <li>Plain text fallback</li>
 * </ul>
 *
 * <p>All failures return empty string — never crashes the pipeline.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcrHttpClient {

    private final OcrProperties props;
    private final ObjectMapper  objectMapper;

    private RestClient restClient;

    @PostConstruct
    void init() {
        if (!props.isRapidocrEnabled()) {
            log.info("OcrHttpClient: RapidOCR container disabled.");
            return;
        }

        int timeoutMs = props.getRapidocrTimeoutSeconds() * 1000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(timeoutMs);

        restClient = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(props.getRapidocrUrl())
                .build();

        log.info("OcrHttpClient initialised: url={}{}, file-field={}, timeout={}s",
                props.getRapidocrUrl(),
                props.getRapidocrApiPath(),
                props.getRapidocrFileField(),
                props.getRapidocrTimeoutSeconds());
    }

    /**
     * Sends image bytes to the RapidOCR server and returns extracted text.
     *
     * @param imageBytes  raw image bytes (PNG, JPEG, TIFF, BMP)
     * @param contentType MIME type of the image
     * @param documentId  for log correlation only
     * @return extracted text, or empty string on any failure
     */
    public String recognizeImage(byte[] imageBytes, String contentType, Object documentId) {
        if (!props.isRapidocrEnabled() || restClient == null) {
            log.debug("OcrHttpClient skipped (RapidOCR disabled): documentId={}", documentId);
            return "";
        }

        log.debug("Sending {} bytes ({}) to RapidOCR server: documentId={}",
                imageBytes.length, contentType, documentId);

        try {
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return "image." + mimeToExtension(contentType);
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add(props.getRapidocrFileField(), imageResource);

            String raw = restClient.post()
                    .uri(props.getRapidocrApiPath())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new OcrServerException(
                                "RapidOCR server returned HTTP " + response.getStatusCode().value());
                    })
                    .body(String.class);

            String text = parseText(raw);
            log.debug("RapidOCR server returned {} chars: documentId={}", text.length(), documentId);
            return text;

        } catch (OcrServerException e) {
            log.warn("RapidOCR server HTTP error: documentId={}, reason={}", documentId, e.getMessage());
            return "";
        } catch (RestClientException e) {
            log.warn("RapidOCR server unreachable: documentId={}, url={}{}, error={}",
                    documentId,
                    props.getRapidocrUrl(),
                    props.getRapidocrApiPath(),
                    e.getMessage());
            return "";
        } catch (Exception e) {
            log.warn("Unexpected RapidOCR error: documentId={}, error={}", documentId, e.getMessage(), e);
            return "";
        }
    }

    /**
     * Parses RapidOCR server response — handles multiple formats:
     * <ul>
     *   <li>Format 1 (PaddleOCR wrappers): {"text":"..."}</li>
     *   <li>Format 2 (RapidOCR): {"0":{"rec_txt":"..."},"1":{"rec_txt":"..."}}</li>
     *   <li>Plain text fallback</li>
     * </ul>
     */
    private String parseText(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String trimmed = raw.strip();

        if (trimmed.startsWith("{")) {
            try {
                JsonNode root = objectMapper.readTree(trimmed);

                // Format 1: {"text":"..."}
                JsonNode text = root.path("text");
                if (!text.isMissingNode() && text.isTextual()) {
                    return text.asText().strip();
                }

                // Format 2: {"0":{"rec_txt":"...","score":0.9},...}
                if (root.isObject() && root.has("0")) {
                    StringBuilder sb = new StringBuilder();
                    root.fields().forEachRemaining(entry -> {
                        JsonNode recTxt = entry.getValue().path("rec_txt");
                        if (recTxt.isTextual() && !recTxt.asText().isBlank()) {
                            if (!sb.isEmpty()) sb.append("\n");
                            sb.append(recTxt.asText());
                        }
                    });
                    if (!sb.isEmpty()) return sb.toString().strip();
                }

                log.debug("JSON response did not match any known RapidOCR format");
            } catch (Exception e) {
                log.debug("Failed to parse RapidOCR response as JSON: {}", e.getMessage());
            }
        }

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

    private static class OcrServerException extends RuntimeException {
        OcrServerException(String msg) { super(msg); }
    }
}
