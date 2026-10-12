package com.grocery.microservices.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitConfigTest {
    private String key(String remote, String forwarded) {
        var props = new GatewayProperties(List.of(), List.of("10.0.0.1", "10.0.0.2"), null, null, null);
        var request = MockServerHttpRequest.get("/api/customer/cart")
                .remoteAddress(new InetSocketAddress(remote, 1234))
                .header("X-Forwarded-For", forwarded).build();
        return new RateLimitConfig(props).ipKeyResolver().resolve(MockServerWebExchange.from(request)).block();
    }
    @Test
    void directCallerCannotSpoofTrustedProxy() {
        assertThat(key("192.0.2.10", "10.0.0.1")).isEqualTo("ip:192.0.2.10");
    }
    @Test
    void trustedProxyUsesNearestUntrustedHopInsteadOfSpoofedLeftmostValue() {
        assertThat(key("10.0.0.1", "spoofed, 192.0.2.10, 10.0.0.2")).isEqualTo("ip:192.0.2.10");
    }
    @Test
    void authenticatedCustomersUseSeparateSubjectBucketsBehindSameBffIp() {
        var props = new GatewayProperties(List.of(), List.of(), null, null, null);
        var resolver = new RateLimitConfig(props).customerKeyResolver();
        for (String subject : List.of("customer-1", "customer-2")) {
            var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("verified")
                    .header("alg", "RS256").subject(subject).build();
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/customer/cart")
                    .remoteAddress(new InetSocketAddress("192.0.2.10", 1234)).build())
                    .mutate().principal(reactor.core.publisher.Mono.just(
                            new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt, List.of())))
                    .build();
            assertThat(resolver.resolve(exchange).block()).isEqualTo("user:" + subject);
        }
    }

}
