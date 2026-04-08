package com.ecm.common.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for calling ecm-admin REST API.
 * Uses X-Internal-Service header for service-to-service authentication bypass.
 */
@Component
@Slf4j
public class AdminServiceClient {

    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final String SERVICE_NAME = "ecm-internal";

    private final RestTemplate restTemplate;
    private final String adminServiceUrl;

    public AdminServiceClient(
            @Value("${ecm.services.admin-url:http://localhost:8086}") String adminServiceUrl
    ) {
        this.restTemplate = new RestTemplate();
        this.adminServiceUrl = adminServiceUrl;
    }

    /**
     * Search customers by name, account number, or other criteria.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> searchCustomers(String query) {
        // Clean query: trim whitespace, trailing punctuation
        String cleanQuery = query != null ? query.replaceAll("[,;.]+$", "").trim() : "";
        if (cleanQuery.isBlank()) return Collections.emptyList();

        String url = adminServiceUrl + "/internal/admin/customers?q=" + cleanQuery;
        HttpHeaders headers = createHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("data")) {
                Object data = body.get("data");
                // Handle both List (flat) and Page (object with content array) responses
                if (data instanceof List) {
                    return (List<Map<String, Object>>) data;
                }
                if (data instanceof Map) {
                    Object content = ((Map<String, Object>) data).get("content");
                    if (content instanceof List) {
                        return (List<Map<String, Object>>) content;
                    }
                }
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to search customers with query '{}': {}", cleanQuery, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Get all document categories.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getCategories() {
        String url = adminServiceUrl + "/internal/admin/categories";
        HttpHeaders headers = createHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("data")) {
                List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                log.debug("Fetched {} categories from admin service", data != null ? data.size() : 0);
                return data != null ? data : Collections.emptyList();
            }
            log.warn("Categories response has no 'data' field: {}", body != null ? body.keySet() : "null");
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Failed to get categories: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Resolve hierarchy (segment, product line) from a category ID.
     * Returns a map with segmentId, productLineId, segmentName, productLineName.
     * Returns empty map if no mapping exists.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> resolveHierarchy(Integer categoryId) {
        if (categoryId == null) return Collections.emptyMap();

        String url = adminServiceUrl + "/internal/admin/hierarchy/resolve?categoryId=" + categoryId;
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                return data != null ? data : Collections.emptyMap();
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to resolve hierarchy for categoryId={}: {}", categoryId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Fetch AI Gateway integration credentials for ecm-ocr to obtain a service JWT
     * and route OCR LLM calls through {@code /api/invoke}.
     *
     * <p>The response shape is {@code {baseUrl, oktaClientId, oktaClientSecret, route}}.
     * The secret is decrypted server-side in ecm-admin before being returned. Callers
     * should cache this for ~60 seconds to avoid hammering ecm-admin.
     *
     * @return map of the four credential fields, or empty map if ecm-admin is unreachable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getAiGatewayOcrCredentials() {
        String url = adminServiceUrl + "/internal/admin/ai-gateway/ocr-credentials";
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        try {
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("data")) {
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                return data != null ? data : Collections.emptyMap();
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to fetch AI Gateway OCR credentials from admin: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_HEADER, SERVICE_NAME);
        return headers;
    }
}
