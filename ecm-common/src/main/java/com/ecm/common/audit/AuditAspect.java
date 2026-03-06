package com.ecm.common.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Parameter;

/**
 * Intercepts @AuditLog-annotated methods.
 * Captures identity + request context BEFORE proceeding (security context clears after),
 * then delegates the DB write to AuditWriter (@Async bean) so the HTTP thread is not blocked.
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class AuditAspect {

    private final AuditWriter auditWriter;

    @Around("@annotation(auditLog)")
    public Object audit(ProceedingJoinPoint pjp, AuditLog auditLog) throws Throwable {

        // Capture BEFORE pjp.proceed() — SecurityContext may clear after response commits
        String entraObjectId = null;
        String userEmail     = null;
        String sessionId     = null;

        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            entraObjectId = jwt.getSubject();
            userEmail     = jwt.getClaimAsString("email");
            sessionId     = jwt.getClaimAsString("sid"); // Okta session ID claim
        }

        String ip         = extractIp();
        String userAgent  = extractUserAgent();
        String resourceId = resolveResourceId(auditLog, pjp);

        try {
            Object result = pjp.proceed();
            auditWriter.write(auditLog, entraObjectId, userEmail,
                    resourceId, sessionId, ip, userAgent, "SUCCESS", null);
            return result;

        } catch (Exception ex) {
            auditWriter.write(auditLog, entraObjectId, userEmail,
                    resourceId, sessionId, ip, userAgent, "FAILURE", ex.getMessage());
            throw ex;
        }
    }

    // ─── Resource ID resolution ───────────────────────────────────────────────

    /**
     * Resolves the resourceId attribute from @AuditLog.
     * If the value starts with '#', looks up the named parameter in the method args.
     * Otherwise treats it as a literal string.
     * Returns null if empty or not resolvable.
     */
    private String resolveResourceId(AuditLog auditLog, ProceedingJoinPoint pjp) {
        String spec = auditLog.resourceId();
        if (spec == null || spec.isEmpty()) return null;

        if (spec.startsWith("#")) {
            String paramName = spec.substring(1); // strip '#'
            try {
                MethodSignature sig = (MethodSignature) pjp.getSignature();
                Parameter[] params  = sig.getMethod().getParameters();
                Object[]    args    = pjp.getArgs();
                for (int i = 0; i < params.length; i++) {
                    if (params[i].getName().equals(paramName) && args[i] != null) {
                        return String.valueOf(args[i]);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not resolve resourceId '{}': {}", spec, e.getMessage());
            }
            return null;
        }

        return spec; // literal value
    }

    // ─── Request context helpers ──────────────────────────────────────────────

    private String extractIp() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest req = attrs.getRequest();
            String forwarded = req.getHeader("X-Forwarded-For");
            return (forwarded != null) ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        } catch (Exception e) { return null; }
    }

    private String extractUserAgent() {
        try {
            var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            return attrs.getRequest().getHeader("User-Agent");
        } catch (Exception e) { return null; }
    }
}