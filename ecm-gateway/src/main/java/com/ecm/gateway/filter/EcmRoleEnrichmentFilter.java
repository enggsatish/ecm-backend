package com.ecm.gateway.filter;

import com.ecm.gateway.client.IdentityEnrichmentClient;
import com.ecm.gateway.dto.EnrichmentResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Gateway-wide role enrichment filter.
 *
 * Runs on every authenticated request (except /actuator/**).
 * Order = -10 (before Spring Cloud Gateway's default routing at order 0,
 *               after Spring Security's JWT validation which is at -100).
 *
 * Full flow per request:
 *   1. Strip all incoming X-ECM-* headers (anti-spoofing)
 *   2. Verify JWT carries ECM_GROUP or ECM_ADMIN Okta group
 *   3. Try Redis cache  → hit: inject headers, forward
 *   4. Redis miss       → call identity /internal/auth/enrich
 *                        NO_ACCESS: 403
 *                        OK: write cache, inject headers, forward
 *   5. Identity down    → ECM_ADMIN: bypass (warn), forward
 *                        others: 503
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EcmRoleEnrichmentFilter implements GlobalFilter, Ordered {

    private static final String CACHE_KEY_PREFIX   = "ecm:user:enrich:";
    private static final Duration CACHE_TTL        = Duration.ofMinutes(15);

    // Header names injected by this filter
    private static final String HDR_ROLES       = "X-ECM-Roles";
    private static final String HDR_PERMISSIONS = "X-ECM-Permissions";
    private static final String HDR_SUBJECT     = "X-ECM-Subject";
    private static final String HDR_EMAIL       = "X-ECM-Email";

    private final ReactiveStringRedisTemplate redis;
    private final IdentityEnrichmentClient    identityClient;
    private final ObjectMapper                objectMapper;

    /** Must run BEFORE Gateway routing (order 0) but AFTER JWT validation (-100) */
    @Override
    public int getOrder() {
        return -10;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        // Always pass actuator through — health probes carry no JWT
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        // STEP 1: Strip incoming X-ECM-* headers (prevent header injection by clients)
        ServerHttpRequest stripped = exchange.getRequest().mutate()
                .headers(h -> {
                    h.remove(HDR_ROLES);
                    h.remove(HDR_PERMISSIONS);
                    h.remove(HDR_SUBJECT);
                    h.remove(HDR_EMAIL);
                })
                .build();
        ServerWebExchange cleanExchange = exchange.mutate().request(stripped).build();

        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .flatMap(auth -> {
                    Jwt jwt = auth.getToken();
                    String sub   = jwt.getSubject();
                    String email = jwt.getClaimAsString("email");

                    List<String> groups = jwt.getClaimAsStringList("groups");
                    boolean hasEcmGroup = groups != null && groups.contains("ECM_GROUP");
                    boolean isOktaAdmin = groups != null && groups.contains("ECM_ADMIN");

                    // STEP 2: Must be an ECM application member
                    if (!hasEcmGroup && !isOktaAdmin) {
                        log.debug("Access denied — not an ECM member, sub={}", sub);
                        return forbidden(cleanExchange, "ECM_NOT_MEMBER",
                                "Not an ECM application user");
                    }

                    String cacheKey = CACHE_KEY_PREFIX + sub;

                    // STEP 3: Try Redis cache
                    return redis.opsForValue().get(cacheKey)
                            .flatMap(cached -> {
                                try {
                                    EnrichmentResponseDto dto =
                                            objectMapper.readValue(cached, EnrichmentResponseDto.class);
                                    log.debug("Cache HIT for sub={}", sub);
                                    return forwardEnriched(cleanExchange, chain, dto, sub, email);
                                } catch (JsonProcessingException e) {
                                    log.warn("Cache deserialization failed for sub={}, fetching fresh", sub);
                                    return Mono.empty();
                                }
                            })
                            .switchIfEmpty(
                                    // STEP 4: Cache miss — call identity service
                                    identityClient.enrich(sub, email)
                                            .flatMap(dto -> {
                                                if ("NO_ACCESS".equals(dto.getStatus())) {
                                                    return forbidden(cleanExchange, "ECM_NO_ACCESS",
                                                            "No ECM roles assigned. Contact your administrator.");
                                                }
                                                // Cache result and forward
                                                return writeCache(cacheKey, dto)
                                                        .then(forwardEnriched(cleanExchange, chain, dto, sub, email));
                                            })
                                            .onErrorResume(ex -> {
                                                // STEP 5: Identity unreachable — degraded mode
                                                log.warn("Identity service unreachable for sub={}: {}",
                                                        sub, ex.getMessage());

                                                if (isOktaAdmin) {
                                                    log.warn("ADMIN BYPASS ACTIVATED — identity service down, sub={}", sub);
                                                    return forwardEnriched(cleanExchange, chain,
                                                            adminBypassDto(), sub, email);
                                                }

                                                log.error("ECM_SERVICE_UNAVAILABLE — identity down, no bypass for sub={}", sub);
                                                return serviceUnavailable(cleanExchange);
                                            })
                            );
                });
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Mono<Void> forwardEnriched(ServerWebExchange exchange,
                                        GatewayFilterChain chain,
                                        EnrichmentResponseDto dto,
                                        String sub, String email) {
        String roles = dto.getRoles() == null ? "" : String.join(",", dto.getRoles());
        String perms = dto.getPermissions() == null ? "" : String.join(",", dto.getPermissions());

        ServerHttpRequest enriched = exchange.getRequest().mutate()
                .header(HDR_ROLES,       roles)
                .header(HDR_PERMISSIONS, perms)
                .header(HDR_SUBJECT,     sub)
                .header(HDR_EMAIL,       email != null ? email : "")
                .build();

        return chain.filter(exchange.mutate().request(enriched).build());
    }

    private Mono<Boolean> writeCache(String key, EnrichmentResponseDto dto) {
        try {
            String json = objectMapper.writeValueAsString(dto);
            return redis.opsForValue().set(key, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize enrichment for caching: {}", e.getMessage());
            return Mono.just(false);
        }
    }

    private Mono<Void> forbidden(ServerWebExchange exchange, String code, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = String.format("{\"success\":false,\"code\":\"%s\",\"message\":\"%s\"}", code, message);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    private Mono<Void> serviceUnavailable(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"success\":false,\"code\":\"ECM_SERVICE_UNAVAILABLE\"," +
                      "\"message\":\"Authentication service temporarily unavailable. Try again.\"}";
        DataBuffer buffer = exchange.getResponse().bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /** Full-permission bypass DTO for ECM_ADMIN when identity service is down */
    private EnrichmentResponseDto adminBypassDto() {
        return EnrichmentResponseDto.builder()
                .status("OK")
                .roles(List.of("ECM_ADMIN"))
                .permissions(List.of(
                    "documents:read",    "documents:write",   "documents:upload",
                    "documents:delete",  "documents:archive", "documents:export",
                    "workflow:view",     "workflow:claim",    "workflow:approve",
                    "workflow:reject",   "workflow:design",   "workflow:admin",
                    "eforms:submit",     "eforms:review",     "eforms:design",   "eforms:admin",
                    "admin:users",       "admin:roles",       "admin:configure",  "admin:audit",
                    "ocr:trigger",       "ocr:view",
                    "archive:read",      "archive:manage"
                ))
                .build();
    }
}
