package com.ecm.admin.controller;

import com.ecm.admin.mcp.McpToolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Legacy MCP HTTP endpoint for the ECM platform.
 *
 * <p>This is the pre-JSON-RPC transport — a simple REST shape where the AI Gateway
 * lists tools via {@code GET /mcp/tools} and executes them via
 * {@code POST /mcp/tools/{name}/execute}. Kept intact during the migration window per
 * {@code HANDOFF_ECM_JSON_RPC_MCP.md} so the gateway can fall back to the
 * {@code LEGACY_HTTP} transport toggle if anything goes wrong with the new JSON-RPC path.
 *
 * <p>After the gateway has been stable on {@code MCP_HTTP} transport for 1-2 weeks,
 * this controller can be deleted. The new path is served by Spring AI's MCP server
 * starter — see {@code com.ecm.admin.mcp.EcmMcpTools}.
 *
 * <p>Both paths share the same business logic via {@link McpToolService} — no duplication.
 */
@Slf4j
@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpServerController {

    private final McpToolService tools;

    @GetMapping("/tools")
    public ResponseEntity<List<Map<String, String>>> listTools() {
        return ResponseEntity.ok(List.of(
                tool("ecm_search_documents", "Search ECM documents by name, category, or status. Returns document ID, name, category, status, and upload date.", "query"),
                tool("ecm_get_document", "Get full details of a document including extracted OCR fields. Pass a document UUID.", "documentId"),
                tool("ecm_search_customers", "Search ECM customers by name. Returns customer ID, name, segment, and email.", "query"),
                tool("ecm_get_customer_documents", "Get all documents belonging to a customer. Pass customer external ID (UUID).", "customerId"),
                tool("ecm_search_cases", "Search cases by reference, customer name, or status. Returns case ID, status, product, and customer.", "query"),
                tool("ecm_get_case_details", "Get full case details including checklist items. Pass a case UUID.", "caseId"),
                tool("ecm_get_pending_tasks", "Get pending workflow tasks across all users. Returns task name, assignee, case reference, and due date.", "query"),
                tool("ecm_get_workflow_instance", "Get workflow instance details including current step and history. Pass a workflow instance UUID.", "instanceId"),
                tool("ecm_search_form_submissions", "Search form submissions by form name or submitter. Returns submission ID, form name, status, and submitted date.", "query"),
                tool("ecm_get_case_timeline", "Get the activity timeline for a case — all events including uploads, workflow steps, and status changes. Pass a case UUID.", "caseId"),
                tool("ecm_get_dashboard_stats", "Get ECM platform statistics — total documents, cases, active workflows, and pending tasks.", "query"),
                tool("ecm_get_products", "Get the product catalog — all products with their document requirements.", "query"),
                tool("ecm_get_categories", "Get all document categories configured in the system.", "query")
        ));
    }

    private Map<String, String> tool(String name, String description, String parameterName) {
        return Map.of("name", name, "description", description, "parameterName", parameterName);
    }

    @PostMapping("/tools/{name}/execute")
    public ResponseEntity<Map<String, Object>> executeTool(
            @PathVariable String name,
            @RequestBody Map<String, String> params) {
        log.info("MCP tool execute (legacy): tool={}, params={}", name, params);

        try {
            String result = switch (name) {
                case "ecm_search_documents"      -> tools.searchDocuments(params.getOrDefault("query", ""));
                case "ecm_get_document"          -> tools.getDocument(params.getOrDefault("documentId", ""));
                case "ecm_search_customers"     -> tools.searchCustomers(params.getOrDefault("query", ""));
                case "ecm_get_customer_documents"-> tools.getDocumentsForCustomer(params.getOrDefault("customerId", ""));
                case "ecm_search_cases"          -> tools.searchCases(params.getOrDefault("query", ""));
                case "ecm_get_case_details"      -> tools.getCase(params.getOrDefault("caseId", ""));
                case "ecm_get_pending_tasks"     -> tools.getPendingTasks(params.getOrDefault("query", ""));
                case "ecm_get_workflow_instance" -> tools.getWorkflowInstance(params.getOrDefault("instanceId", ""));
                case "ecm_search_form_submissions"-> tools.searchFormSubmissions(params.getOrDefault("query", ""));
                case "ecm_get_case_timeline"     -> tools.getCaseTimeline(params.getOrDefault("caseId", ""));
                case "ecm_get_dashboard_stats"   -> tools.getPlatformStats();
                case "ecm_get_products"          -> tools.getProductCatalog();
                case "ecm_get_categories"        -> tools.getDocumentCategories();
                default -> "Unknown tool: " + name;
            };
            return ResponseEntity.ok(Map.of("result", result));
        } catch (Exception e) {
            log.error("MCP tool {} failed: {}", name, e.getMessage());
            return ResponseEntity.ok(Map.of("result", "Error: " + e.getMessage()));
        }
    }
}
