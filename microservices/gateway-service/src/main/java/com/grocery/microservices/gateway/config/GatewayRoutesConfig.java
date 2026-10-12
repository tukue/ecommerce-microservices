package com.grocery.microservices.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Programmatic route definitions for the grocery API gateway.
 *
 * <p>Routes are defined in Java (not YAML) to demonstrate fluency with the
 * {@link RouteLocatorBuilder} DSL and to keep the route table type-safe and
 * refactorable without a context restart to parse YAML.</p>
 *
 * <h3>Route table</h3>
 * <pre>
 *  Public path                          Upstream              Auth    StripPrefix
 *  ──────────────────────────────────── ──────────────────── ─────── ───────────
 *  GET  /api/catalog/**                 product-service       no      2
 *  POST /api/auth/login                 BFF                   no      —
 *  POST /api/auth/logout                BFF                   no      —
 *  GET  /api/auth/me                    BFF                   yes     —
 *  **   /api/customer/cart/**           cart-service          yes     —
 *  POST /api/customer/checkout          order-service         yes     —
 *  **   /api/customer/orders/**        order-service         yes     —
 *  **   /api/customer/ledger/**        ledger-service        yes     —
 * </pre>
 *
 * <h3>Cross-cutting filters (applied globally, not per-route)</h3>
 * <ul>
 *   <li>{@code X-Correlation-Id} — injected per-request by
 *       {@link com.grocery.microservices.gateway.filter.CorrelationIdFilter}.</li>
 * </ul>
 *
 * <h3>Per-route filters</h3>
 * <ul>
 *   <li>{@code StripPrefix(2)} — used only where upstream controller paths
 *       intentionally omit the public prefix.</li>
 *   <li>{@code X-Gateway-Version: 1.0} — static marker in upstream logs.</li>
 *   <li>{@code RequestRateLimiter} — Redis token-bucket on all customer routes.</li>
 * </ul>
 *
 * <h3>Circuit breaker (stub)</h3>
 * Add {@code spring-cloud-starter-circuitbreaker-reactor-resilience4j} and
 * uncomment the {@code .circuitBreaker()} calls to enable per-route fallbacks.
 *
 * <h3>Bean ownership</h3>
 * {@link KeyResolver} and {@link RedisRateLimiter} are declared in
 * {@link RateLimitConfig} and injected here — this class only builds routes.
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayRoutesConfig {

    private final GatewayProperties props;
    private final KeyResolver ipKeyResolver;
    private final RedisRateLimiter customerRateLimiter;

    public GatewayRoutesConfig(GatewayProperties props,
                               KeyResolver ipKeyResolver,
                               RedisRateLimiter customerRateLimiter) {
        this.props = props;
        this.ipKeyResolver = ipKeyResolver;
        this.customerRateLimiter = customerRateLimiter;
    }

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        GatewayProperties.Services svc = props.services();

        return builder.routes()

                // ── 1. Product catalog — public ───────────────────────────────────
                // StripPrefix(2): /api/catalog/products → /products
                .route("catalog-products", r -> r
                        .path("/api/catalog/**")
                        .filters(f -> f
                                .stripPrefix(2)
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                // .circuitBreaker(c -> c.setName("product-cb")
                                //     .setFallbackUri("forward:/fallback/catalog"))
                        )
                        .uri(svc.productUrl())
                )

                // ── 2. Auth login — public, forwarded to Node.js BFF ──────────────
                .route("auth-login", r -> r
                        .path("/api/auth/login")
                        .and().method("POST")
                        .uri(svc.bffUrl())
                )

                // ── 3. Auth logout — public, BFF clears the session cookie ─────────
                .route("auth-logout", r -> r
                        .path("/api/auth/logout")
                        .and().method("POST")
                        .uri(svc.bffUrl())
                )

                // ── 4. Auth me — JWT enforced by gateway, session resolved by BFF ──
                .route("auth-me", r -> r
                        .path("/api/auth/me")
                        .and().method("GET")
                        .uri(svc.bffUrl())
                )

                .route("stripe-webhook", r -> r
                        .path("/api/webhooks/stripe")
                        .and().method("POST")
                        .filters(f -> f.stripPrefix(1))
                        .uri(svc.orderUrl())
                )

                // ── 5. Cart — protected, rate limited ─────────────────────────────
                // Preserve the customer API prefix expected by CartController.
                .route("customer-cart", r -> r
                        .path("/api/customer/cart", "/api/customer/cart/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(ipKeyResolver)
                                )
                                // .circuitBreaker(c -> c.setName("cart-cb")
                                //     .setFallbackUri("forward:/fallback/cart"))
                        )
                        .uri(svc.cartUrl())
                )

                // ── 6. Checkout — protected, rate limited ─────────────────────────
                // Preserve the customer API prefix expected by OrderController.
                .route("customer-checkout", r -> r
                        .path("/api/customer/checkout")
                        .and().method("POST")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(ipKeyResolver)
                                )
                                // .circuitBreaker(c -> c.setName("order-cb")
                                //     .setFallbackUri("forward:/fallback/order"))
                        )
                        .uri(svc.orderUrl())
                )

                // ── 7. Order history — protected, rate limited ────────────────────
                .route("customer-orders", r -> r
                        .path("/api/customer/orders", "/api/customer/orders/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(ipKeyResolver)
                                )
                                // .circuitBreaker(c -> c.setName("order-cb")
                                //     .setFallbackUri("forward:/fallback/order"))
                        )
                        .uri(svc.orderUrl())
                )

                // ── 8. Ledger / receipts — protected, rate limited ────────────────
                .route("customer-ledger", r -> r
                        .path("/api/customer/ledger", "/api/customer/ledger/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(c -> c
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(ipKeyResolver)
                                )
                                // .circuitBreaker(c -> c.setName("ledger-cb")
                                //     .setFallbackUri("forward:/fallback/ledger"))
                        )
                        .uri(svc.summaryUrl())
                )

                .build();
    }
}
