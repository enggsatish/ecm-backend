package com.ecm.batch.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * HTTP client for calling ecm-eforms REST API.
 * Uses X-Internal-Service header for service-to-service authentication bypass.
 */
@Component
@Slf4j
public class EformsServiceClient {

    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final String SERVICE_NAME = "ecm-batch";

    private final RestTemplate restTemplate;
    private final String eformsServiceUrl;

    public EformsServiceClient(
            @Value("${ecm.services.eforms-url:http://localhost:8084}") String eformsServiceUrl
    ) {
        this.restTemplate = new RestTemplate();
        this.eformsServiceUrl = eformsServiceUrl;
    }

    /**
     * Resolve the document category configured for a published form, by formKey.
     * Used by the QR fast-path: an eForms-generated QR only encodes the form key,
     * not a category directly — this looks up what that form maps to.
     *
     * @return documentCategoryId, or null if the form has none configured or the
     *         lookup fails (caller should fall through to normal OCR classification).
     */
    public Integer getDocumentCategoryIdForForm(String formKey) {
        String url = eformsServiceUrl + "/api/eforms/internal/definitions/" + formKey + "/category";
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_HEADER, SERVICE_NAME);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Object body = response.getBody();
            if (body instanceof Map<?, ?> outer) {
                Object data = outer.get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    Object catId = dataMap.get("documentCategoryId");
                    if (catId instanceof Number n) return n.intValue();
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Failed to resolve documentCategoryId for formKey={}: {}", formKey, e.getMessage());
            return null;
        }
    }
}
