package com.grocery.microservices.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Strongly-typed binding for all {@code gateway.*} configuration properties.
 *
 * <p>Using a {@code @ConfigurationProperties} record rather than scattered
 * {@code @Value} annotations means:</p>
 * <ul>
 *   <li>All gateway config is visible in one place.</li>
 *   <li>Values are validated at startup — a missing required property fails fast
 *       with a clear message rather than a NullPointerException at runtime.</li>
 *   <li>The record can be injected into tests directly, making config-dependent
 *       behaviour testable without spinning up a full Spring context.</li>
 * </ul>
 *
 * <p>All fields have safe defaults so the service starts in the {@code dev}
 * profile without external dependencies. The {@code docker} profile overrides
 * these via environment-variable resolution in
 * {@code application-docker.properties}.</p>
 */
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(

/**
     * Origins the gateway will include in CORS allow-origin responses.
     * Must contain at least one entry — startup fails otherwise (see SecurityConfig).
     * Example: {@code http://localhost:5173,http://localhost:3000}
     */
    List<String> corsAllowedOrigins,

    /** IP addresses of trusted reverse proxies that may set X-Forwarded-For. */
    List<String> trustedProxies,

    /** Downstream service base URLs. */
    Services services,

        /** JWT validation settings (mirrors the resource-server services). */
        Jwt jwt,

        /** Token-bucket rate limiting parameters. */
        RateLimit rateLimit

) {

    /**
     * Base URLs of all upstream services.
     *
     * <p>{@code bffUrl} points at the Node.js BFF which handles login and
     * session-cookie management. All other URLs point at Spring Boot services.</p>
     */
    public record Services(
            String cartUrl,
            String orderUrl,
            String productUrl,
            String summaryUrl,
            String bffUrl
    ) {}

    /**
     * JWT validation config.
     *
     * <p>{@code issuerUri} identifies the trusted issuer; {@code jwksUri} explicitly
     * selects its public signing-key endpoint, supporting OIDC providers whose
     * key endpoint does not follow the demo issuer convention.</p>
     */
    public record Jwt(
            String issuerUri,
            String audience,
            String jwksUri
    ) {}

    /**
     * Token-bucket parameters for the Redis-backed {@code RequestRateLimiter}.
     *
     * @param replenishRate   tokens added to the bucket per second
     * @param burstCapacity   maximum tokens the bucket can hold (allows short bursts)
     * @param requestedTokens tokens consumed per request (1 for normal requests)
     */
    public record RateLimit(
            int replenishRate,
            int burstCapacity,
            int requestedTokens
    ) {
        public RateLimit {
            if (replenishRate <= 0) throw new IllegalArgumentException("replenishRate must be > 0");
            if (burstCapacity < replenishRate)
                throw new IllegalArgumentException("burstCapacity must be >= replenishRate");
            if (requestedTokens <= 0) throw new IllegalArgumentException("requestedTokens must be > 0");
        }
    }
}
