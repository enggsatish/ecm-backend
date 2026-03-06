package com.ecm.common.config;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class AudienceValidator
        implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public AudienceValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        System.out.println("🔍 Token audience: " + jwt.getAudience());
        System.out.println("🎯 Expected audience: " + audience);

        if (jwt.getAudience().contains(audience)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "Token audience does not match. " +
                        "Token has: " + jwt.getAudience() +
                        " Expected: " + audience,
                "https://tools.ietf.org/html/rfc6750#section-3.1"
        );
        return OAuth2TokenValidatorResult.failure(error);
    }
}