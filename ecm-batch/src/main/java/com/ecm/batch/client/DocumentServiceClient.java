package com.ecm.batch.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for calling ecm-document REST API.
 * Uses X-Internal-Service header for service-to-service authentication bypass.
 */
@Component
@Slf4j
public class DocumentServiceClient {

    private static final String INTERNAL_HEADER = "X-Internal-Service";
    private static final String SERVICE_NAME = "ecm-batch";

    private final RestTemplate restTemplate;
    private final String documentServiceUrl;

    public DocumentServiceClient(
            @Value("${ecm.services.document-url:http://localhost:8082}") String documentServiceUrl
    ) {
        this.restTemplate = new RestTemplate();
        this.documentServiceUrl = documentServiceUrl;
    }

    /**
     * Get document metadata by ID.
     */
    public Map<String, Object> getDocument(UUID documentId) {
        String url = documentServiceUrl + "/api/documents/" + documentId;
        HttpHeaders headers = createHeaders();
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        try {
            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            log.error("Failed to get document {}: {}", documentId, e.getMessage());
            throw new RuntimeException("Failed to get document from ecm-document service", e);
        }
    }

    /**
     * Update document metadata (category, customer, classification source).
     * <p>
     * Sends: categoryId, partyExternalId, classificationSource, classificationConfidence.
     * Note: If the ecm-document service does not yet have this endpoint, a 404 is handled
     * gracefully with a warning log instead of propagating the error.
     */
    public void updateDocumentMetadata(UUID documentId, Map<String, Object> metadata) {
        String url = documentServiceUrl + "/api/documents/" + documentId + "/metadata";
        HttpHeaders headers = createHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(metadata, headers);

        try {
            restTemplate.exchange(url, HttpMethod.PUT, entity, Void.class);
            log.debug("Updated metadata for document {}", documentId);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Document metadata endpoint not found (404) for document {} — " +
                    "the ecm-document service may not have this endpoint yet", documentId);
        } catch (Exception e) {
            log.error("Failed to update metadata for document {}: {}", documentId, e.getMessage());
            throw new RuntimeException("Failed to update document metadata", e);
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(INTERNAL_HEADER, SERVICE_NAME);
        return headers;
    }
}
