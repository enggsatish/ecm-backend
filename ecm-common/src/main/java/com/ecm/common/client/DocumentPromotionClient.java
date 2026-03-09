package com.ecm.common.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Internal HTTP client for promoting form submission PDFs to the document store.
 *
 * Called by ecm-eforms (WorkflowCompletedListener + FormSubmissionService dev mode).
 * Uses ecm-document's existing POST /api/documents/upload multipart endpoint,
 * ensuring OCR events, OpenSearch indexing, and audit log all fire correctly.
 *
 * ── Why no @ConditionalOnProperty ────────────────────────────────────────────
 *
 * RestTemplate is always provided by InternalWebClientConfig (declared in ecm-common
 * with @ConditionalOnMissingBean so it doesn't conflict with module-level beans).
 * Because RestTemplate is always available, this bean can always be created safely
 * across all modules — modules that don't inject it simply never call it.
 *
 * ── Gateway auth note ────────────────────────────────────────────────────────
 *
 * Internal calls set X-Internal-Service: ecm-eforms. The gateway must allow
 * POST /api/documents/upload with this header without requiring an Okta JWT.
 * For PoC: add the internal service header to gateway's permit list.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentPromotionClient {

    private final RestTemplate restTemplate;

    @Value("${ecm.services.document-url:http://localhost:8082}")
    private String documentServiceUrl;

    /**
     * Uploads a PDF byte array to ecm-document as a new document record.
     *
     * @param pdfBytes         raw PDF content
     * @param filename         e.g. "mortgage-application-{submissionId}.pdf"
     * @param displayName      e.g. "Mortgage Application — John Smith"
     * @param submittedByEmail used as uploadedByEmail on the document
     * @param partyExternalId  soft ref to party (null → triggers backoffice triage queue)
     * @param categoryId       document category (may be null)
     * @return UUID of the created document, or null on failure (caller logs + handles)
     */
    public UUID promote(byte[] pdfBytes, String filename, String displayName,
                        String submittedByEmail, String partyExternalId, Integer categoryId) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();

            // File part
            ByteArrayResource fileResource = new ByteArrayResource(pdfBytes) {
                @Override
                public String getFilename() { return filename; }
            };
            HttpHeaders fileHeaders = new HttpHeaders();
            fileHeaders.setContentType(MediaType.APPLICATION_PDF);
            fileHeaders.setContentDispositionFormData("files", filename);
            body.add("files", new HttpEntity<>(fileResource, fileHeaders));

            // Metadata part — JSON string matching DocumentUploadRequest record fields
            String metadata = buildMetadataJson(displayName, partyExternalId, categoryId);
            HttpHeaders metaHeaders = new HttpHeaders();
            metaHeaders.setContentType(MediaType.APPLICATION_JSON);
            body.add("metadata", new HttpEntity<>(metadata, metaHeaders));

            // Request headers
            HttpHeaders requestHeaders = new HttpHeaders();
            requestHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
            requestHeaders.set("X-Internal-Service", "ecm-eforms");

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    documentServiceUrl + "/api/documents/upload",
                    new HttpEntity<>(body, requestHeaders),
                    Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                // ApiResponse<T> envelope: { success, data: { id, ... }, message }
                Object data = response.getBody().get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    Object id = dataMap.get("id");
                    if (id != null) {
                        return UUID.fromString(id.toString());
                    }
                }
            }

            log.error("[DocumentPromotionClient] Unexpected response status={} for filename={}",
                    response.getStatusCode(), filename);
            return null;

        } catch (Exception e) {
            log.error("[DocumentPromotionClient] Failed to promote '{}' to ecm-document: {}",
                    displayName, e.getMessage(), e);
            return null;
        }
    }

    private String buildMetadataJson(String name, String partyExternalId, Integer categoryId) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"name\":\"").append(escape(name)).append("\"");
        if (partyExternalId != null && !partyExternalId.isBlank()) {
            sb.append(",\"partyExternalId\":\"").append(escape(partyExternalId)).append("\"");
        }
        if (categoryId != null) {
            sb.append(",\"categoryId\":").append(categoryId);
        }
        sb.append("}");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}