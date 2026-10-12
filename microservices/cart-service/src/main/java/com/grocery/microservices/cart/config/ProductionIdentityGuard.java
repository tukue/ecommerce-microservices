package com.grocery.microservices.cart.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast guard for production identity configuration.
 *
 * <p>Production must run against an external OIDC provider. This guard rejects
 * a configuration that would enable the development identity mechanism, use a
 * non-HTTPS issuer, or omit the required audience, before the service serves
 * traffic. Messages never include configured values.</p>
 */
@Configuration
@Profile("prod")
@EnableConfigurationProperties(JwtProperties.class)
public class ProductionIdentityGuard {

    public ProductionIdentityGuard(JwtProperties jwt) {
        requireSafeProductionIdentity(jwt.demoEnabled(), jwt.issuerUri(), jwt.audience());
    }

    static void requireSafeProductionIdentity(boolean demoEnabled, String issuerUri, String audience) {
        if (demoEnabled) {
            throw new IllegalStateException(
                    "Production identity configuration error: security.jwt.demo-enabled must be false");
        }
        if (issuerUri == null || issuerUri.isBlank()
                || !issuerUri.startsWith("https://")) {
            throw new IllegalStateException(
                    "Production identity configuration error: security.jwt.issuer-uri must be an https issuer URI");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "Production identity configuration error: security.jwt.audience must be set");
        }
    }
}
