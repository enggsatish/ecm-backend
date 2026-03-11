package com.ecm.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * JWT → Spring Security authorities converter.
 *
 * Sprint G change:
 *   PRODUCTION: Reads X-ECM-Roles and X-ECM-Permissions headers set by
 *               EcmRoleEnrichmentFilter in ecm-gateway. These headers are
 *               stripped at the gateway before any client-supplied values can
 *               reach downstream services (anti-spoofing).
 *
 *   LOCAL DEV:  When spring.profiles.active=local (running without the gateway),
 *               falls back to reading the Okta "groups" JWT claim directly.
 *               This keeps all local dev workflows functional.
 *
 * Existing @PreAuthorize("hasRole('ECM_ADMIN')") annotations are UNCHANGED.
 * EcmJwtConverter still emits ROLE_ECM_* authorities — only the source changes.
 */
@Slf4j
@Component
public class EcmJwtConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter defaultConverter =
            new JwtGrantedAuthoritiesConverter();

    @Value("${spring.profiles.active:prod}")
    private String activeProfile;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        // Standard scope authorities (SCOPE_*) from the JWT — always included
        Collection<GrantedAuthority> scopeAuthorities = defaultConverter.convert(jwt);
        if (scopeAuthorities != null) {
            authorities.addAll(scopeAuthorities);
        }

        // Try to read enriched headers (injected by gateway EcmRoleEnrichmentFilter)
        HttpServletRequest httpReq = getHttpRequest();
        String rolesHeader = httpReq != null ? httpReq.getHeader("X-ECM-Roles")       : null;
        String permsHeader = httpReq != null ? httpReq.getHeader("X-ECM-Permissions") : null;

        if (rolesHeader != null && !rolesHeader.isBlank()) {
            // ── PRODUCTION PATH: Gateway has enriched this request ────────────
            Arrays.stream(rolesHeader.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                    .forEach(authorities::add);

            if (permsHeader != null && !permsHeader.isBlank()) {
                Arrays.stream(permsHeader.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(p -> new SimpleGrantedAuthority("PERMISSION_" + p))
                        .forEach(authorities::add);
            }

            log.debug("Enriched authorities from gateway headers for principal={}", extractPrincipalName(jwt));

        } else if ("local".equals(activeProfile)) {
            // ── LOCAL DEV FALLBACK: No gateway — read Okta groups from JWT ────
            List<String> groups = jwt.getClaimAsStringList("groups");
            if (groups != null) {
                groups.stream()
                        .filter(g -> g.startsWith("ECM_"))
                        .map(g -> new SimpleGrantedAuthority("ROLE_" + g))
                        .forEach(authorities::add);
            }
            log.warn("LOCAL PROFILE: using JWT groups fallback (no gateway enrichment). " +
                     "Permissions will not be available.");
        } else {
            // No headers and not local — results in empty authorities → all @PreAuthorize will fail → 403
            log.debug("No X-ECM-* headers and not local profile — empty role set for principal={}",
                      extractPrincipalName(jwt));
        }

        String principalName = extractPrincipalName(jwt);
        log.debug("User [{}] final authorities: {}", principalName, authorities);

        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private String extractPrincipalName(Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getSubject();
    }

    private HttpServletRequest getHttpRequest() {
        try {
            return ((ServletRequestAttributes)
                    RequestContextHolder.currentRequestAttributes()).getRequest();
        } catch (Exception e) {
            return null;
        }
    }
}
