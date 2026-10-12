package com.grocery.microservices.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * WebFlux security configuration for the gateway.
 *
 * <h3>CSRF</h3>
 * Disabled. The gateway is a stateless OAuth2 resource server — clients
 * authenticate via {@code Authorization: Bearer}. There are no session cookies
 * managed at this layer, so the CSRF double-submit pattern does not apply.
 *
 * <h3>JWT validation</h3>
 * The gateway validates every inbound token before routing:
 * <ol>
 *   <li>Signature — verified against the JWKS published by the demo identity
 *       provider (cart-service in dev/docker). Algorithm is RS256.</li>
 *   <li>Timestamps — {@code exp} and {@code nbf} checked by
 *       {@link JwtTimestampValidator}.</li>
 *   <li>Issuer — must match {@code gateway.jwt.issuer-uri}.</li>
 *   <li>Audience — must contain {@code gateway.jwt.audience} (typically
 *       {@code grocery-api}).</li>
 * </ol>
 * Validated tokens are forwarded downstream unchanged in the
 * {@code Authorization} header. Services perform their own validation as a
 * defence-in-depth measure.
 *
 * <h3>Public paths</h3>
 * <ul>
 *   <li>CORS pre-flight OPTIONS on any path</li>
 *   <li>{@code /actuator/health}, {@code /actuator/info},
 *       {@code /actuator/prometheus}</li>
 *   <li>{@code GET /api/catalog/**} — product browsing does not require login</li>
 * </ul>
 *
 * <h3>Profile guard</h3>
 * {@code @Profile("!test")} ensures tests supply their own permissive security
 * chain via {@code TestSecurityConfig} without needing a live JWT issuer.
 */
@Configuration
@EnableWebFluxSecurity
@Profile("!test")
public class SecurityConfig {

    private final GatewayProperties props;

    public SecurityConfig(GatewayProperties props) {
        this.props = props;
    }

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeExchange(exchange -> exchange
                        // CORS pre-flight must pass before any auth check
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Operational endpoints: no auth required
                        .pathMatchers(
                                "/actuator/health",
                                "/actuator/info",
                                "/actuator/prometheus"
                        ).permitAll()
                        // Public product catalog: browse without a token
                        .pathMatchers(HttpMethod.GET, "/api/catalog/**").permitAll()
                        // Stripe authenticates webhook requests with its signature header.
                        .pathMatchers(HttpMethod.POST, "/api/webhooks/stripe").permitAll()
                        // Everything else requires a valid, unexpired JWT with the
                        // correct issuer and audience
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder()))
                )
                .build();
    }

    /**
     * Reactive JWT decoder with a full validation chain.
     *
     * <p>Uses JWKS discovery from the configured issuer URI so no signing key is
     * stored in this service. Key rotation on the identity provider side is
     * transparent — the decoder fetches the current JWKS on the next request.</p>
     */
    @Bean
    public ReactiveJwtDecoder reactiveJwtDecoder() {
        String jwksUri = props.jwt().issuerUri() + "/.well-known/jwks.json";

        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder
                .withJwkSetUri(jwksUri)
                .build();

        decoder.setJwtValidator(jwtValidator());
        return decoder;
    }

    /**
     * Composite JWT validator: timestamps + issuer + audience.
     *
     * <p>All three must pass. The audience check ensures tokens minted for a
     * different service (e.g., an admin API) are rejected even if they are
     * signed by the same key.</p>
     */
    private OAuth2TokenValidator<Jwt> jwtValidator() {
        String issuer  = props.jwt().issuerUri();
        String audience = props.jwt().audience();

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException(
                    "gateway.jwt.issuer-uri must be set when JWT validation is active");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException(
                    "gateway.jwt.audience must be set when JWT validation is active");
        }

        // Audience validator: token's 'aud' claim must contain the configured audience
        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<List<String>>(
                        "aud",
                        aud -> aud != null && aud.contains(audience)
                );

        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer),
                audienceValidator
        );
    }

    /**
     * Reactive CORS configuration.
     *
     * <p>Allowed origins come from {@code gateway.cors-allowed-origins} — set
     * per environment, never wildcarded. Services behind the gateway do not need
     * their own CORS policies for browser traffic.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = props.corsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            throw new IllegalStateException(
                    "gateway.cors-allowed-origins must have at least one entry");
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(
                List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(
                List.of("Authorization", "Content-Type", "X-Correlation-Id", "Idempotency-Key"));
        config.setExposedHeaders(List.of("Location", "X-Correlation-Id"));
        // credentials=false: gateway uses bearer tokens, not cookies
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
