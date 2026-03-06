package com.ecm.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

/**
 * Separated from AuditAspect to fix the @Async self-invocation problem.
 * See original class comment for full explanation.
 *
 * Now writes resource_id and session_id in addition to original columns.
 * Schema column mapping:
 *
 *   event_type      ← auditLog.event()
 *   entra_object_id ← JWT subject
 *   user_email      ← JWT email claim
 *   resource_type   ← auditLog.resourceType()
 *   resource_id     ← resolved from @AuditLog(resourceId="#id") [NEW]
 *   ip_address      ← X-Forwarded-For or remote addr
 *   user_agent      ← HTTP User-Agent header
 *   severity        ← auditLog.severity()
 *   session_id      ← JWT 'sid' claim (Okta session) [NEW]
 *   payload         ← JSON with outcome + optional error
 *   created_at      ← now()
 *
 * Columns intentionally NOT written (require app-layer lookup, avoid N+1):
 *   user_id        ← internal DB user PK (not in JWT; skipped for async safety)
 *   department_id  ← requires user lookup (skipped for async safety)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditWriter {

    private final JdbcTemplate jdbcTemplate;

    @Async("auditExecutor")
    public void write(AuditLog auditLog,
                      String subjectId,
                      String email,
                      String resourceId,
                      String sessionId,
                      String ip,
                      String userAgent,
                      String outcome,
                      String error) {
        try {
            jdbcTemplate.update("""
                INSERT INTO ecm_audit.audit_log
                    (event_type, entra_object_id, user_email,
                     resource_type, resource_id,
                     ip_address, user_agent,
                     severity, session_id, payload, created_at)
                VALUES (?,?,?,?,?,?::inet,?,?,?,?::jsonb,?)
                """,
                    auditLog.event(),
                    subjectId,
                    email,
                    auditLog.resourceType(),
                    resourceId,
                    ip,
                    userAgent,
                    auditLog.severity(),
                    sessionId,
                    buildPayload(outcome, error),
                    OffsetDateTime.now()
            );
        } catch (Exception e) {
            // Audit failure must NEVER propagate to the calling thread
            log.error("Audit write failed [event={}, subject={}]: {}",
                    auditLog.event(), subjectId, e.getMessage());
        }
    }

    private String buildPayload(String outcome, String error) {
        if (error != null) {
            String safe = error.replace("\\", "\\\\")
                    .replace("\"", "'")
                    .replace("\n", " ")
                    .replace("\r", "");
            // Truncate to prevent oversized payloads
            if (safe.length() > 500) safe = safe.substring(0, 500) + "...";
            return String.format("{\"outcome\":\"%s\",\"error\":\"%s\"}", outcome, safe);
        }
        return String.format("{\"outcome\":\"%s\"}", outcome);
    }
}