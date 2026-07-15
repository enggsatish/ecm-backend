package com.ecm.admin.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * ECM tools exposed to the AI Gateway via real MCP JSON-RPC 2.0 over HTTP+SSE.
 *
 * <p>Each method is declared via Spring AI's {@code @Tool} annotation. At runtime,
 * Spring AI's MCP server starter discovers these methods through the
 * {@code ToolCallbackProvider} bean wired in {@link McpServerConfig}, auto-generates
 * JSON Schema input specs from the method signatures, and exposes them as real MCP
 * tools via the {@code tools/list} and {@code tools/call} JSON-RPC methods.
 *
 * <p><b>Tool naming — camelCase matching the gateway handoff.</b>
 * The handoff specifies these exact names because the chat model may have seen them
 * in prior testing conversations. If ECM's legacy controller uses {@code ecm_snake_case}
 * names, the new MCP path uses camelCase. Both controllers share the same business
 * logic via {@link McpToolService}, so the wire names differ but the behavior is identical.
 *
 * <p><b>Strictly read-only.</b> Every tool is a SELECT query. No mutations. This is
 * deliberate — see {@code project_ai_chat_security_gaps.md} item #4. If any future tool
 * needs to write to the DB, it should require a separate confirmation flow and audit
 * log entry, not be exposed via MCP.
 *
 * <p><b>Authentication:</b> the Spring AI MCP server starter exposes its endpoints
 * under {@code /mcp/sse} and {@code /mcp/message} (configured in {@code application.yml}).
 * These paths are already gated by {@code AdminSecurityConfig}'s {@code X-Internal-Service}
 * header check on {@code /mcp/**}, so the gateway's existing auth flow applies unchanged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcmMcpTools {

    private final McpToolService tools;

    @Tool(name = "searchDocuments",
          description = "Search ECM documents by name, category, or status. Returns document ID, name, category, status, and upload date.")
    public String searchDocuments(
            @ToolParam(description = "Search text — words are matched against document name, filename, category, and customer name") String query) {
        log.info("MCP tool (JSON-RPC): searchDocuments query='{}'", query);
        return tools.searchDocuments(query);
    }

    @Tool(name = "getDocument",
          description = "Get full details of an ECM document including extracted OCR fields and classification. Pass a document UUID.")
    public String getDocument(
            @ToolParam(description = "Document UUID") String documentId) {
        log.info("MCP tool (JSON-RPC): getDocument documentId='{}'", documentId);
        return tools.getDocument(documentId);
    }

    @Tool(name = "searchCustomers",
          description = "Search ECM customers by name, external ID, or email. Returns customer ID, name, segment, and email.")
    public String searchCustomers(
            @ToolParam(description = "Search text for customer name, external ID, or email") String query) {
        log.info("MCP tool (JSON-RPC): searchCustomers query='{}'", query);
        return tools.searchCustomers(query);
    }

    @Tool(name = "getDocumentsForCustomer",
          description = "Get all documents belonging to a customer. Pass the customer's external ID (not their internal UUID).")
    public String getDocumentsForCustomer(
            @ToolParam(description = "Customer external ID") String customerId) {
        log.info("MCP tool (JSON-RPC): getDocumentsForCustomer customerId='{}'", customerId);
        return tools.getDocumentsForCustomer(customerId);
    }

    @Tool(name = "searchCases",
          description = "Search cases by external reference, customer name, or product. Returns case ID, status, product, and customer.")
    public String searchCases(
            @ToolParam(description = "Search text for case reference, customer, or product") String query) {
        log.info("MCP tool (JSON-RPC): searchCases query='{}'", query);
        return tools.searchCases(query);
    }

    @Tool(name = "getCase",
          description = "Get full case details including checklist items, assignment, and status. Pass a case UUID.")
    public String getCase(
            @ToolParam(description = "Case UUID") String caseId) {
        log.info("MCP tool (JSON-RPC): getCase caseId='{}'", caseId);
        return tools.getCase(caseId);
    }

    @Tool(name = "getPendingTasks",
          description = "Get pending workflow tasks across all users. Returns task name, assignee, case reference, and creation date. Optional query filter.")
    public String getPendingTasks(
            @ToolParam(description = "Optional search filter by task name, assignee, or case ref. Leave empty for all.", required = false) String query) {
        log.info("MCP tool (JSON-RPC): getPendingTasks query='{}'", query);
        return tools.getPendingTasks(query);
    }

    @Tool(name = "getWorkflowInstance",
          description = "Get workflow instance details including current step and activity history. Pass a workflow instance ID.")
    public String getWorkflowInstance(
            @ToolParam(description = "Workflow instance ID (Flowable process instance ID)") String instanceId) {
        log.info("MCP tool (JSON-RPC): getWorkflowInstance instanceId='{}'", instanceId);
        return tools.getWorkflowInstance(instanceId);
    }

    @Tool(name = "searchFormSubmissions",
          description = "Search form submissions by form name or submitter. Returns submission ID, form name, status, and submitted date.")
    public String searchFormSubmissions(
            @ToolParam(description = "Search text for form name or submitter email") String query) {
        log.info("MCP tool (JSON-RPC): searchFormSubmissions query='{}'", query);
        return tools.searchFormSubmissions(query);
    }

    @Tool(name = "getCaseTimeline",
          description = "Get the activity timeline for a case — all events including uploads, workflow steps, and status changes. Pass a case UUID.")
    public String getCaseTimeline(
            @ToolParam(description = "Case UUID") String caseId) {
        log.info("MCP tool (JSON-RPC): getCaseTimeline caseId='{}'", caseId);
        return tools.getCaseTimeline(caseId);
    }

    @Tool(name = "getPlatformStats",
          description = "Get ECM platform statistics — total documents, cases, active cases, pending tasks, and active customers.")
    public String getPlatformStats() {
        log.info("MCP tool (JSON-RPC): getPlatformStats");
        return tools.getPlatformStats();
    }

    @Tool(name = "getProductCatalog",
          description = "Get the product catalog — all active products with their required document type counts.")
    public String getProductCatalog() {
        log.info("MCP tool (JSON-RPC): getProductCatalog");
        return tools.getProductCatalog();
    }

    @Tool(name = "getDocumentCategories",
          description = "Get all active document categories configured in the ECM system.")
    public String getDocumentCategories() {
        log.info("MCP tool (JSON-RPC): getDocumentCategories");
        return tools.getDocumentCategories();
    }
}
