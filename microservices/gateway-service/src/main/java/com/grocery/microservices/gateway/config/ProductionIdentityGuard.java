package com.grocery.microservices.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Fail-fast guard for the gateway's production identity configuration.
 *
 * <p>Production must validate tokens from an external OIDC provider over
 * HTTPS. This guard rejects a missing/non-HTTPS issuer or a blank audience
 * before the gateway serves traffic. Messages never include configured
 * values.</p>
 */
@Configuration
@Profile("prod")
@EnableConfigurationProperties(GatewayProperties.class)
public class ProductionIdentityGuard {

    public ProductionIdentityGuard(GatewayProperties props) {
        requireSafeProductionIdentity(props.jwt().issuerUri(), props.jwt().audience());
    }

    static void requireSafeProductionIdentity(String issuerUri, String audience) {
        if (issuerUri == null || issuerUri.isBlank()
                || !issuerUri.startsWith("https://")) {
            throw new IllegalStateException(
                    "Production identity configuration error: gateway.jwt.issuer-uri must be an https issuer URI");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "Production identity configuration error: gateway.jwt.audience must be set");
        }
    }
}
