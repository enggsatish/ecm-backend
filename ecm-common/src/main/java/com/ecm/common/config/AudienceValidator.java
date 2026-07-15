package com.ecm.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@Slf4j
public class AudienceValidator
        implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }

        log.debug("Token audience mismatch for subject={}", jwt.getSubject());
        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "Token audience does not match expected value",
                "https://tools.ietf.org/html/rfc6750#section-3.1"
        );
        return OAuth2TokenValidatorResult.failure(error);
    }
}
