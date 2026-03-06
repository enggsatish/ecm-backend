package com.ecm.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
public class EcmJwtConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter defaultConverter =
            new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {

        // Standard scope-based authorities
        Collection<GrantedAuthority> scopeAuthorities =
                defaultConverter.convert(jwt);

        // Okta puts groups in "groups" claim
        // e.g. ["ECM_ADMIN", "ECM_BACKOFFICE", "Everyone"]
        Collection<GrantedAuthority> groupAuthorities =
                extractGroupAuthorities(jwt);

        Set<GrantedAuthority> all = Stream.of(
                        scopeAuthorities, groupAuthorities)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        String principalName = extractPrincipalName(jwt);

        log.debug("User [{}] authorities: {}", principalName, all);

        return new JwtAuthenticationToken(jwt, all, principalName);
    }

    private Collection<GrantedAuthority> extractGroupAuthorities(Jwt jwt) {
        // Okta sends groups as a list in the "groups" claim
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups == null) return Collections.emptyList();

        return groups.stream()
                // Only map groups that start with ECM_ — ignore "Everyone" etc
                .filter(g -> g.startsWith("ECM_"))
                .map(g -> new SimpleGrantedAuthority("ROLE_" + g.toUpperCase()))
                .collect(Collectors.toList());
    }

    private String extractPrincipalName(Jwt jwt) {
        // Okta uses "sub" as unique ID and "email" as readable name
        String email = jwt.getClaimAsString("email");
        return email != null ? email : jwt.getSubject();
    }
}