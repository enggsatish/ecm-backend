package com.ecm.admin.controller;

import com.ecm.common.model.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Audit log queries — reads from ecm_audit.audit_log (cross-schema read).
 *
 * Uses JdbcTemplate because ecm-admin's Hibernate default_schema is ecm_admin,
 * and we need to explicitly qualify ecm_audit.audit_log in queries.
 * JPA entity mapping for a cross-schema read-only table adds unnecessary complexity.
 *
 * Dynamic WHERE clause built from optional request params.
 * Max page size is capped at 100 to prevent runaway queries.
 */
@RestController
@RequestMapping("/api/admin/audit")
@PreAuthorize("hasRole('ECM_ADMIN')")
@RequiredArgsConstructor
public class AuditLogController {

    private final JdbcTemplate jdbc;

    /**
     * GET /api/admin/audit
     *
     * Query params (all optional):
     *   userId        — filter by exact user ID string
     *   event         — filter by event type  (e.g. DOCUMENT_UPLOAD)
     *   resourceType  — filter by resource type (e.g. DOCUMENT)
     *   severity      — INFO | WARN | ERROR
     *   from          — ISO-8601 datetime lower bound (inclusive)
     *   to            — ISO-8601 datetime upper bound (inclusive)
     *   page          — 0-based page number (default 0)
     *   size          — page size (default 50, max 100)
     *
     * Returns a page envelope: { content, totalElements, totalPages, page, size }
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> queryAudit(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String event,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {

        // Build dynamic WHERE clause
        List<Object> params  = new ArrayList<>();
        StringBuilder where  = new StringBuilder(" WHERE 1=1 ");

        if (userId       != null) { where.append(" AND entra_object_id = ?"); params.add(userId); }
        if (event        != null) { where.append(" AND event_type = ?");       params.add(event); }
        if (resourceType != null) { where.append(" AND resource_type = ?");    params.add(resourceType); }
        if (severity     != null) { where.append(" AND severity = ?");         params.add(severity); }
        if (from         != null) { where.append(" AND created_at >= ?");    params.add(from); }
        if (to           != null) { where.append(" AND created_at <= ?");    params.add(to); }

        // COUNT — uses the same WHERE params list (clone before adding LIMIT/OFFSET)
        String countSql = "SELECT COUNT(*) FROM ecm_audit.audit_log" + where;
        Long total = jdbc.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) total = 0L;

        // DATA — append pagination params after counting
        int effectiveSize = Math.min(size, 100);
        String dataSql =
                "SELECT id, entra_object_id, user_email, event_type, resource_type, resource_id, " +
                        "severity, payload, ip_address, user_agent, session_id, created_at " +
                        "FROM ecm_audit.audit_log" + where +
                        " ORDER BY created_at DESC LIMIT ? OFFSET ?";

        params.add(effectiveSize);
        params.add((long) page * effectiveSize);

        List<Map<String, Object>> rows = jdbc.queryForList(dataSql, params.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content",       rows);
        result.put("totalElements", total);
        result.put("totalPages",    (int) Math.ceil((double) total / effectiveSize));
        result.put("page",          page);
        result.put("size",          effectiveSize);

        return ResponseEntity.ok(ApiResponse.ok(result));
    }
}