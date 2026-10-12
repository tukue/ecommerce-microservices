package com.grocery.microservices.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Explicit gateway routes for public catalog, authentication, customer APIs,
 * and Stripe's signed webhook endpoint.
 */
@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class GatewayRoutesConfig {
    private final GatewayProperties props;
    private final KeyResolver customerKeyResolver;
    private final RedisRateLimiter customerRateLimiter;

    public GatewayRoutesConfig(GatewayProperties props,
                               @Qualifier("customerKeyResolver") KeyResolver customerKeyResolver,
                               RedisRateLimiter customerRateLimiter) {
        this.props = props;
        this.customerKeyResolver = customerKeyResolver;
        this.customerRateLimiter = customerRateLimiter;
    }

    @Bean
    public RouteLocator gatewayRoutes(RouteLocatorBuilder builder) {
        GatewayProperties.Services services = props.services();
        return builder.routes()
                .route("catalog-products", route -> route
                        .path("/api/catalog/products", "/api/catalog/products/search",
                                "/api/catalog/products/{id:[0-9]+}")
                        .and().method("GET")
                        .filters(filters -> filters
                                .stripPrefix(2)
                                .addRequestHeader("X-Gateway-Version", "1.0"))
                        .uri(services.productUrl()))
                .route("auth-login", route -> route
                        .path("/api/auth/login")
                        .and().method("POST")
                        .uri(services.bffUrl()))
                .route("auth-logout", route -> route
                        .path("/api/auth/logout")
                        .and().method("POST")
                        .uri(services.bffUrl()))
                .route("auth-me", route -> route
                        .path("/api/auth/me")
                        .and().method("GET")
                        .uri(services.bffUrl()))
                .route("stripe-webhook", route -> route
                        .path("/api/webhooks/stripe")
                        .and().method("POST")
                        .filters(filters -> filters.stripPrefix(1))
                        .uri(services.orderUrl()))
                .route("customer-cart", route -> route
                        .path("/api/customer/cart", "/api/customer/cart/**")
                        .filters(filters -> filters
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(customerKeyResolver)))
                        .uri(services.cartUrl()))
                .route("customer-checkout", route -> route
                        .path("/api/customer/checkout")
                        .and().method("POST")
                        .filters(filters -> filters
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(customerKeyResolver)))
                        .uri(services.orderUrl()))
                .route("customer-orders", route -> route
                        .path("/api/customer/orders", "/api/customer/orders/**")
                        .filters(filters -> filters
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(customerKeyResolver)))
                        .uri(services.orderUrl()))
                .route("customer-ledger", route -> route
                        .path("/api/customer/ledger",
                                "/api/customer/ledger/orders/{id:[0-9]+}/receipt")
                        .and().method("GET")
                        .filters(filters -> filters
                                .addRequestHeader("X-Gateway-Version", "1.0")
                                .requestRateLimiter(config -> config
                                        .setRateLimiter(customerRateLimiter)
                                        .setKeyResolver(customerKeyResolver)))
                        .uri(services.summaryUrl()))
                .build();
    }
}
