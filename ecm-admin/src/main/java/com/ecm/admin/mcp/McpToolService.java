package com.ecm.admin.mcp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared business-logic layer for the ECM MCP tool catalogue.
 *
 * <p>Two controllers call into this service:
 * <ul>
 *   <li>{@code McpServerController} — legacy REST shape at {@code /mcp/tools} + {@code /mcp/tools/{name}/execute}.
 *       Kept during the migration window per {@code HANDOFF_ECM_JSON_RPC_MCP.md} so the AI Gateway can
 *       fall back to {@code LEGACY_HTTP} transport if anything goes wrong with the new path.</li>
 *   <li>{@code EcmMcpTools} — new {@code @Tool}-annotated class exposed via Spring AI's MCP server
 *       starter. This is the JSON-RPC 2.0 / HTTP+SSE path the gateway will use once the admin
 *       flips the per-server {@code transport} toggle from {@code LEGACY_HTTP} to {@code MCP_HTTP}.</li>
 * </ul>
 *
 * <p>All tools are strictly read-only SELECT queries. No mutations. This matches what
 * {@code project_ai_chat_security_gaps.md} item #4 requires for safe MCP tool exposure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class McpToolService {

    private final JdbcTemplate jdbc;

    public String searchDocuments(String query) {
        // Split query into words — match ANY word against name, filename, category, or customer
        String[] words = query == null ? new String[0] : query.trim().split("\\s+");
        StringBuilder where = new StringBuilder("d.status != 'DELETED'");
        List<Object> params = new ArrayList<>();

        for (String word : words) {
            if (word.length() < 2) continue;
            String like = "%" + word + "%";
            where.append(" AND (d.name ILIKE ? OR d.original_filename ILIKE ? OR dc.name ILIKE ? OR p.display_name ILIKE ?)");
            params.add(like);
            params.add(like);
            params.add(like);
            params.add(like);
        }

        List<Map<String, Object>> docs = jdbc.queryForList(
                "SELECT d.id, d.name, d.status, d.mime_type, " +
                "dc.name AS category_name, d.created_at, d.party_external_id, p.display_name AS customer_name " +
                "FROM ecm_core.documents d " +
                "LEFT JOIN ecm_admin.document_categories dc ON dc.id = d.category_id " +
                "LEFT JOIN ecm_core.parties p ON p.external_id = d.party_external_id " +
                "WHERE " + where +
                " ORDER BY d.created_at DESC LIMIT 10",
                params.toArray());

        if (docs.isEmpty()) return "No documents found matching: " + query;

        StringBuilder sb = new StringBuilder("Found " + docs.size() + " document(s):\n");
        for (var doc : docs) {
            sb.append(String.format("• %s | Status: %s | Category: %s | Customer: %s | ID: %s | Uploaded: %s\n",
                    doc.get("name"), doc.get("status"),
                    doc.get("category_name") != null ? doc.get("category_name") : "Uncategorized",
                    doc.get("customer_name") != null ? doc.get("customer_name") : "N/A",
                    doc.get("id"), doc.get("created_at")));
        }
        return sb.toString();
    }

    public String getDocument(String documentId) {
        try {
            UUID id = UUID.fromString(documentId.trim());
            List<Map<String, Object>> docs = jdbc.queryForList("""
                    SELECT d.id, d.name, d.status, d.mime_type, d.extracted_text,
                           d.extracted_fields, d.classification_source, d.classification_confidence,
                           dc.name AS category_name, d.party_external_id, d.created_at
                    FROM ecm_core.documents d
                    LEFT JOIN ecm_admin.document_categories dc ON dc.id = d.category_id
                    WHERE d.id = ?
                    """, id);

            if (docs.isEmpty()) return "Document not found: " + documentId;

            var doc = docs.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Document: ").append(doc.get("name")).append("\n");
            sb.append("Status: ").append(doc.get("status")).append("\n");
            sb.append("Category: ").append(doc.get("category_name") != null ? doc.get("category_name") : "None").append("\n");
            sb.append("Customer: ").append(doc.get("party_external_id") != null ? doc.get("party_external_id") : "Not linked").append("\n");

            if (doc.get("extracted_fields") != null) {
                sb.append("Extracted Fields: ").append(doc.get("extracted_fields")).append("\n");
            }
            if (doc.get("extracted_text") != null) {
                String text = doc.get("extracted_text").toString();
                sb.append("OCR Text (first 500 chars): ").append(text, 0, Math.min(500, text.length()));
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Invalid document ID: " + documentId;
        }
    }

    public String searchCustomers(String query) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        List<Map<String, Object>> customers = jdbc.queryForList("""
                SELECT p.id, p.external_id, p.display_name, p.party_type,
                       s.name AS segment_name
                FROM ecm_core.parties p
                LEFT JOIN ecm_admin.segments s ON s.id = p.segment_id
                WHERE p.display_name ILIKE ? OR p.external_id ILIKE ?
                ORDER BY p.display_name LIMIT 10
                """, like, like);

        if (customers.isEmpty()) return "No customers found matching: " + query;

        StringBuilder sb = new StringBuilder("Found " + customers.size() + " customer(s):\n");
        for (var c : customers) {
            sb.append(String.format("• %s | Segment: %s | Type: %s | ID: %s\n",
                    c.get("display_name"),
                    c.get("segment_name") != null ? c.get("segment_name") : "N/A",
                    c.get("party_type") != null ? c.get("party_type") : "N/A",
                    c.get("external_id")));
        }
        return sb.toString();
    }

    public String getDocumentsForCustomer(String customerId) {
        List<Map<String, Object>> docs = jdbc.queryForList("""
                SELECT d.id, d.name, d.status, dc.name AS category_name, d.created_at
                FROM ecm_core.documents d
                LEFT JOIN ecm_admin.document_categories dc ON dc.id = d.category_id
                WHERE d.party_external_id = ? AND d.status != 'DELETED'
                ORDER BY d.created_at DESC LIMIT 20
                """, customerId == null ? "" : customerId.trim());

        if (docs.isEmpty()) return "No documents found for customer: " + customerId;

        StringBuilder sb = new StringBuilder("Customer has " + docs.size() + " document(s):\n");
        for (var doc : docs) {
            sb.append(String.format("• %s | %s | %s | ID: %s\n",
                    doc.get("name"), doc.get("category_name") != null ? doc.get("category_name") : "Uncategorized",
                    doc.get("status"), doc.get("id")));
        }
        return sb.toString();
    }

    public String searchCases(String query) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        List<Map<String, Object>> cases = jdbc.queryForList("""
                SELECT c.id, c.external_ref, c.status, c.case_type,
                       p.display_name AS customer_name, pr.display_name AS product_name
                FROM ecm_core.cases c
                LEFT JOIN ecm_core.parties p ON p.id = c.party_id
                LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
                WHERE c.external_ref ILIKE ? OR p.display_name ILIKE ? OR pr.display_name ILIKE ?
                ORDER BY c.created_at DESC LIMIT 10
                """, like, like, like);

        if (cases.isEmpty()) return "No cases found matching: " + query;

        StringBuilder sb = new StringBuilder("Found " + cases.size() + " case(s):\n");
        for (var c : cases) {
            sb.append(String.format("• %s | Status: %s | Customer: %s | Product: %s | ID: %s\n",
                    c.get("external_ref"), c.get("status"),
                    c.get("customer_name") != null ? c.get("customer_name") : "N/A",
                    c.get("product_name") != null ? c.get("product_name") : "N/A",
                    c.get("id")));
        }
        return sb.toString();
    }

    public String getCase(String caseId) {
        try {
            UUID id = UUID.fromString(caseId.trim());
            List<Map<String, Object>> cases = jdbc.queryForList("""
                    SELECT c.id, c.external_ref, c.status, c.case_type, c.assigned_to, c.claimed_by,
                           c.created_at, c.updated_at,
                           p.display_name AS customer_name, pr.display_name AS product_name
                    FROM ecm_core.cases c
                    LEFT JOIN ecm_core.parties p ON p.id = c.party_id
                    LEFT JOIN ecm_admin.products pr ON pr.id = c.product_id
                    WHERE c.id = ?
                    """, id);

            if (cases.isEmpty()) return "Case not found: " + caseId;

            var c = cases.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Case: ").append(c.get("external_ref")).append("\n");
            sb.append("Status: ").append(c.get("status")).append("\n");
            sb.append("Type: ").append(c.get("case_type")).append("\n");
            sb.append("Customer: ").append(c.get("customer_name") != null ? c.get("customer_name") : "N/A").append("\n");
            sb.append("Product: ").append(c.get("product_name") != null ? c.get("product_name") : "N/A").append("\n");
            sb.append("Assigned to: ").append(c.get("assigned_to") != null ? c.get("assigned_to") : "Unassigned").append("\n");
            sb.append("Created: ").append(c.get("created_at")).append("\n");

            List<Map<String, Object>> checklist = jdbc.queryForList("""
                    SELECT cd.id, pdt.name, cd.status, cd.document_id
                    FROM ecm_core.case_documents cd
                    JOIN ecm_admin.product_document_types pdt ON pdt.id = cd.product_document_type_id
                    WHERE cd.case_id = ?
                    ORDER BY pdt.sort_order
                    """, id);

            if (!checklist.isEmpty()) {
                sb.append("\nChecklist (").append(checklist.size()).append(" items):\n");
                for (var item : checklist) {
                    String status = item.get("document_id") != null ? "Provided" : "Missing";
                    sb.append(String.format("  %s %s — %s\n",
                            "Provided".equals(status) ? "[x]" : "[ ]",
                            item.get("name"), status));
                }
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Invalid case ID: " + caseId;
        }
    }

    public String getPendingTasks(String query) {
        String sql;
        Object[] params;
        if (query != null && !query.isBlank()) {
            String like = "%" + query.trim() + "%";
            sql = """
                    SELECT t.id_, t.name_, t.assignee_, t.create_time_,
                           v.text_ AS case_ref
                    FROM ecm_workflow.act_ru_task t
                    LEFT JOIN ecm_workflow.act_ru_variable v ON v.proc_inst_id_ = t.proc_inst_id_ AND v.name_ = 'caseExternalRef'
                    WHERE (t.name_ ILIKE ? OR t.assignee_ ILIKE ? OR v.text_ ILIKE ?)
                    ORDER BY t.create_time_ DESC LIMIT 15
                    """;
            params = new Object[]{like, like, like};
        } else {
            sql = """
                    SELECT t.id_, t.name_, t.assignee_, t.create_time_,
                           v.text_ AS case_ref
                    FROM ecm_workflow.act_ru_task t
                    LEFT JOIN ecm_workflow.act_ru_variable v ON v.proc_inst_id_ = t.proc_inst_id_ AND v.name_ = 'caseExternalRef'
                    ORDER BY t.create_time_ DESC LIMIT 15
                    """;
            params = new Object[]{};
        }

        List<Map<String, Object>> tasks = jdbc.queryForList(sql, params);
        if (tasks.isEmpty()) return "No pending tasks found.";

        StringBuilder sb = new StringBuilder("Found " + tasks.size() + " pending task(s):\n");
        for (var t : tasks) {
            sb.append(String.format("  %s | Assignee: %s | Case: %s | Created: %s | ID: %s\n",
                    t.get("name_"),
                    t.get("assignee_") != null ? t.get("assignee_") : "Unassigned",
                    t.get("case_ref") != null ? t.get("case_ref") : "N/A",
                    t.get("create_time_"), t.get("id_")));
        }
        return sb.toString();
    }

    public String getWorkflowInstance(String instanceId) {
        try {
            List<Map<String, Object>> instances = jdbc.queryForList("""
                    SELECT e.id_, e.proc_def_id_, e.start_time_, e.end_time_,
                           e.start_user_id_, e.business_key_
                    FROM ecm_workflow.act_hi_procinst e
                    WHERE e.id_ = ?
                    """, instanceId == null ? "" : instanceId.trim());

            if (instances.isEmpty()) return "Workflow instance not found: " + instanceId;

            var inst = instances.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append("Workflow Instance: ").append(inst.get("id_")).append("\n");
            sb.append("Definition: ").append(inst.get("proc_def_id_")).append("\n");
            sb.append("Started by: ").append(inst.get("start_user_id_") != null ? inst.get("start_user_id_") : "System").append("\n");
            sb.append("Business Key: ").append(inst.get("business_key_") != null ? inst.get("business_key_") : "N/A").append("\n");
            sb.append("Started: ").append(inst.get("start_time_")).append("\n");
            sb.append("Ended: ").append(inst.get("end_time_") != null ? inst.get("end_time_") : "Still active").append("\n");

            List<Map<String, Object>> activities = jdbc.queryForList("""
                    SELECT a.act_name_, a.act_type_, a.start_time_, a.end_time_, a.assignee_
                    FROM ecm_workflow.act_hi_actinst a
                    WHERE a.proc_inst_id_ = ?
                    ORDER BY a.start_time_ ASC
                    """, instanceId.trim());

            if (!activities.isEmpty()) {
                sb.append("\nActivity History (").append(activities.size()).append(" steps):\n");
                for (var a : activities) {
                    String name = a.get("act_name_") != null ? a.get("act_name_").toString() : a.get("act_type_").toString();
                    String assignee = a.get("assignee_") != null ? " [" + a.get("assignee_") + "]" : "";
                    String end = a.get("end_time_") != null ? a.get("end_time_").toString() : "in progress";
                    sb.append(String.format("  %s%s — %s\n", name, assignee, end));
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Error looking up workflow: " + e.getMessage();
        }
    }

    public String searchFormSubmissions(String query) {
        String like = "%" + (query == null ? "" : query.trim()) + "%";
        List<Map<String, Object>> subs = jdbc.queryForList("""
                SELECT fs.id, fd.name AS form_name, fs.status, fs.submitted_by, fs.submitted_at
                FROM ecm_forms.form_submissions fs
                JOIN ecm_forms.form_definitions fd ON fd.id = fs.form_definition_id
                WHERE fd.name ILIKE ? OR fs.submitted_by ILIKE ?
                ORDER BY fs.submitted_at DESC LIMIT 10
                """, like, like);

        if (subs.isEmpty()) return "No form submissions found matching: " + query;

        StringBuilder sb = new StringBuilder("Found " + subs.size() + " submission(s):\n");
        for (var s : subs) {
            sb.append(String.format("  %s | Status: %s | By: %s | Date: %s | ID: %s\n",
                    s.get("form_name"), s.get("status"),
                    s.get("submitted_by"), s.get("submitted_at"), s.get("id")));
        }
        return sb.toString();
    }

    public String getCaseTimeline(String caseId) {
        try {
            UUID id = UUID.fromString(caseId.trim());
            List<Map<String, Object>> events = jdbc.queryForList("""
                    SELECT event_type, description, detail, actor, timestamp
                    FROM ecm_core.case_timeline_events
                    WHERE case_id = ?
                    ORDER BY timestamp DESC LIMIT 30
                    """, id);

            if (events.isEmpty()) return "No timeline events found for case: " + caseId;

            StringBuilder sb = new StringBuilder("Timeline for case " + caseId + " (" + events.size() + " events):\n");
            for (var e : events) {
                sb.append(String.format("  [%s] %s — %s (%s)\n",
                        e.get("event_type"),
                        e.get("description") != null ? e.get("description") : "",
                        e.get("actor") != null ? e.get("actor") : "System",
                        e.get("timestamp")));
            }
            return sb.toString();
        } catch (IllegalArgumentException e) {
            return "Invalid case ID: " + caseId;
        } catch (Exception e) {
            return "Timeline unavailable: " + e.getMessage();
        }
    }

    public String getPlatformStats() {
        try {
            Integer docCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.documents WHERE status != 'DELETED'", Integer.class);
            Integer caseCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.cases", Integer.class);
            Integer activeCases = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.cases WHERE status NOT IN ('CLOSED','CANCELLED','REJECTED')", Integer.class);
            Integer taskCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_workflow.act_ru_task", Integer.class);
            Integer customerCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM ecm_core.parties WHERE is_active = true", Integer.class);

            return String.format("""
                    ECM Platform Statistics:
                      Documents: %d total
                      Cases: %d total (%d active)
                      Pending Tasks: %d
                      Active Customers: %d
                    """, docCount, caseCount, activeCases, taskCount, customerCount);
        } catch (Exception e) {
            return "Dashboard stats unavailable: " + e.getMessage();
        }
    }

    public String getProductCatalog() {
        List<Map<String, Object>> products = jdbc.queryForList("""
                SELECT p.id, p.display_name, p.product_code, p.is_active,
                       (SELECT COUNT(*) FROM ecm_admin.product_document_types pdt WHERE pdt.product_id = p.id) AS doc_type_count
                FROM ecm_admin.products p
                WHERE p.is_active = true
                ORDER BY p.display_name
                """);

        if (products.isEmpty()) return "No products configured.";

        StringBuilder sb = new StringBuilder("Product Catalog (" + products.size() + " products):\n");
        for (var p : products) {
            sb.append(String.format("  %s (%s) — %s required document types | ID: %s\n",
                    p.get("display_name"), p.get("product_code"),
                    p.get("doc_type_count"), p.get("id")));
        }
        return sb.toString();
    }

    public String getDocumentCategories() {
        List<Map<String, Object>> cats = jdbc.queryForList("""
                SELECT id, name, code, is_active
                FROM ecm_admin.document_categories
                WHERE is_active = true
                ORDER BY name
                """);

        if (cats.isEmpty()) return "No document categories configured.";

        StringBuilder sb = new StringBuilder("Document Categories (" + cats.size() + "):\n");
        for (var c : cats) {
            sb.append(String.format("  %s (code: %s) | ID: %s\n",
                    c.get("name"), c.get("code"), c.get("id")));
        }
        return sb.toString();
    }
}
