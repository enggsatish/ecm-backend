package com.ecm.admin.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import java.util.*;

/**
 * HTTP client for calling ecm-workflow admin endpoints.
 *
 * Auth: shared internal API key (X-Internal-Token header).
 * Degrades gracefully — if workflow service is down, methods log and return empty/void.
 */
@Service
public class WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClient.class);
    private final RestClient restClient;

    public WorkflowClient(
            @Value("${ecm.services.workflow-url:http://localhost:8083}") String workflowUrl,
            @Value("${ecm.internal.api-key:ecm-internal-dev-key}") String internalApiKey) {
        this.restClient = RestClient.builder()
                .baseUrl(workflowUrl)
                .defaultHeader("X-Internal-Token", internalApiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<WorkflowDefinitionSummary> getDefinitions() {
        try {
            Map<?, ?> response = restClient.get()
                    .uri("/api/workflow/admin/definitions")
                    .retrieve()
                    .body(Map.class);
            if (response == null || !Boolean.TRUE.equals(response.get("success")))
                return Collections.emptyList();
            Object data = response.get("data");
            if (data instanceof List<?> list) {
                return list.stream().filter(item -> item instanceof Map).map(item -> {
                    Map<?, ?> m = (Map<?, ?>) item;
                    WorkflowDefinitionSummary s = new WorkflowDefinitionSummary();
                    s.setId(toInteger(m.get("id")));
                    s.setName(String.valueOf(m.get("name")));
                    s.setProcessKey(String.valueOf(m.get("processKey")));
                    return s;
                }).toList();
            }
            return Collections.emptyList();
        } catch (RestClientException e) {
            log.warn("Unable to fetch workflow definitions (service may be down): {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    public void createCategoryMapping(Integer categoryId, Integer workflowDefinitionId) {
        try {
            restClient.post()
                    .uri("/api/workflow/categories/mappings")
                    .body(Map.of("categoryId", categoryId, "workflowDefinitionId", workflowDefinitionId))
                    .retrieve().toBodilessEntity();
            log.info("Created workflow category mapping: categoryId={}, definitionId={}", categoryId, workflowDefinitionId);
        } catch (RestClientException e) {
            log.error("Failed to create workflow category mapping: {}", e.getMessage());
        }
    }

    public void deleteCategoryMapping(Integer mappingId) {
        try {
            restClient.delete()
                    .uri("/api/workflow/categories/mappings/{id}", mappingId)
                    .retrieve().toBodilessEntity();
            log.info("Deleted workflow category mapping: mappingId={}", mappingId);
        } catch (RestClientException e) {
            log.error("Failed to delete workflow category mapping {}: {}", mappingId, e.getMessage());
        }
    }

    private Integer toInteger(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) return Integer.parseInt(s);
        return null;
    }

    public static class WorkflowDefinitionSummary {
        private Integer id;
        private String name;
        private String processKey;
        public Integer getId() { return id; }
        public void setId(Integer id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getProcessKey() { return processKey; }
        public void setProcessKey(String processKey) { this.processKey = processKey; }
    }
}
