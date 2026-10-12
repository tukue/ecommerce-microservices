package com.grocery.microservices.gateway.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-only Spring configuration active under the {@code test} profile.
 *
 * <p>Provides two overrides needed to run gateway tests without external
 * infrastructure:</p>
 *
 * <ol>
 *   <li><b>Security chain</b> — replaces {@link SecurityConfig}
 *       (excluded via {@code @Profile("!test")}) with a permissive chain that
 *       allows all requests. Route and header tests can focus on routing
 *       behaviour without needing a live JWT issuer.</li>
 *
 *   <li><b>Rate limiter stub</b> — replaces the {@link RedisRateLimiter} bean
 *       declared in {@link RateLimitConfig} with a no-op implementation that
 *       always allows requests. This prevents Spring from attempting a Redis
 *       connection during tests even though Redis autoconfiguration is excluded
 *       in {@code application-test.properties}.</li>
 * </ol>
 */
@TestConfiguration
@EnableWebFluxSecurity
@Profile("test")
public class TestSecurityConfig {

    /**
     * Permissive security chain: all requests are permitted, CSRF and CORS
     * disabled. Tests that need to verify 401 behaviour should be written as
     * separate slice tests with a mock JWKS server.
     */
    @Bean
    @Primary
    public SecurityWebFilterChain testSecurityFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .authorizeExchange(exchange -> exchange.anyExchange().permitAll())
                .build();
    }

    /**
     * No-op rate limiter stub.
     *
     * <p>Always returns an {@code isAllowed=true} response with placeholder
     * token counts so the {@code RequestRateLimiter} filter in the route
     * definitions does not reject any test request and does not attempt a
     * Redis connection.</p>
     */
    @Bean
    @Primary
    public RedisRateLimiter testRateLimiter() {
        return new RedisRateLimiter(100, 200, 1) {
            @Override
            public Mono<Response> isAllowed(String routeId, String id) {
                Map<String, String> headers = new HashMap<>();
                headers.put(REMAINING_HEADER, String.valueOf(99L));
                headers.put(REPLENISH_RATE_HEADER, String.valueOf(100L));
                headers.put(BURST_CAPACITY_HEADER, String.valueOf(200L));
                headers.put(REQUESTED_TOKENS_HEADER, String.valueOf(1L));
                return Mono.just(new Response(true, headers));
            }
        };
    }
}
