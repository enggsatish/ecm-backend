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
 * Auth: X-Internal-Service header (standard inter-service pattern).
 * Degrades gracefully — if workflow service is down, methods log and return empty/void.
 */
@Service
public class WorkflowClient {

    private static final Logger log = LoggerFactory.getLogger(WorkflowClient.class);
    private final RestClient restClient;

    public WorkflowClient(
            @Value("${ecm.services.workflow-url:http://localhost:8083}") String workflowUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(workflowUrl)
                .defaultHeader("X-Internal-Service", "ecm-admin")
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

    private Integer toInteger(Object o) {
        if (o instanceof Integer i) return i;
        if (o instanceof Number n) return n.intValue();
        if (o instanceof String s) return Integer.parseInt(s);
        return null;
    }

    /**
     * Starts a workflow instance via ecm-workflow REST API.
     * Returns the process instance ID, or null on failure.
     */
    @SuppressWarnings("unchecked")
    public StartWorkflowResult startWorkflow(String documentId, String documentName,
                                              Integer workflowDefinitionId, Integer categoryId,
                                              String startedBy) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("documentId", documentId);
            body.put("documentName", documentName != null ? documentName : "");
            body.put("workflowDefinitionId", workflowDefinitionId);
            if (categoryId != null) body.put("categoryId", categoryId);

            Map<?, ?> response = restClient.post()
                    .uri("/api/workflow/instances/internal-start?startedBy={startedBy}",
                         startedBy != null ? startedBy : "system")
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !Boolean.TRUE.equals(response.get("success"))) {
                log.warn("Workflow start returned non-success: {}", response);
                return null;
            }

            Object data = response.get("data");
            if (data instanceof Map<?, ?> m) {
                return new StartWorkflowResult(
                        String.valueOf(m.get("processInstanceId")),
                        String.valueOf(m.get("id")),
                        String.valueOf(m.get("status"))
                );
            }
            return null;
        } catch (RestClientException e) {
            log.error("Failed to start workflow via ecm-workflow: {}", e.getMessage());
            return null;
        }
    }

    public record StartWorkflowResult(String processInstanceId, String instanceRecordId, String status) {}

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
