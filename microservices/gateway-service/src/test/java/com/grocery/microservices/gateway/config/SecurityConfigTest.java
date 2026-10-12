package com.grocery.microservices.gateway.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.WebFilterChainProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/** Exercises the production filter chain and decoder with genuinely signed JWTs. */
@SpringJUnitConfig(SecurityConfigTest.Config.class)
// Exercise the production chain even when CI sets spring.profiles.active=test.
@ActiveProfiles("security-verification")
class SecurityConfigTest {
    static final String ISSUER = "https://identity.example.test";
    static final RSAKey KEY;
    static final MockWebServer JWKS = new MockWebServer();
    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
            JWKS.start();
            JWKS.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    return new MockResponse().addHeader("Content-Type", "application/json")
                            .setBody(new JWKSet(KEY.toPublicJWK()).toString());
                }
            });
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Configuration
    @EnableWebFlux
    @Import(SecurityConfig.class)
    static class Config {
        @Bean
        GatewayProperties gatewayProperties() {
            return new GatewayProperties(List.of("https://shop.example.test"), List.of(), null,
                    new GatewayProperties.Jwt(ISSUER, "grocery-api", JWKS.url("/keys").toString()), null);
        }
        @Bean
        RouterFunction<ServerResponse> endpoints() {
            return route().GET("/api/customer/cart", request -> ServerResponse.ok().bodyValue("cart"))
                    .GET("/api/catalog/products", request -> ServerResponse.ok().bodyValue("catalog"))
                    .POST("/api/catalog/products", request -> ServerResponse.ok().bodyValue("write"))
                    .POST("/api/auth/login", request -> ServerResponse.ok().bodyValue("login"))
                    .build();
        }
    }

    @Autowired SecurityWebFilterChain chain;
    WebTestClient client;

    @BeforeEach
    void client() {
        client = WebTestClient.bindToWebHandler(exchange -> exchange.getResponse().setComplete())
                .webFilter(new WebFilterChainProxy(chain))
                .configureClient().baseUrl("http://gateway.example.test").build();
    }
    @AfterAll
    static void shutdown() throws Exception { JWKS.shutdown(); }

    @Test
    void onlyReadOnlyCatalogAndBffLoginAreAnonymous() {
        client.get().uri("/api/catalog/products").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
        client.get().uri("/actuator/prometheus").exchange().expectStatus().isUnauthorized();
        client.get().uri("/actuator/info").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/customer/cart").exchange().expectStatus().isUnauthorized();
        client.post().uri("/api/catalog/products").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void acceptsTrustedSignedToken() throws Exception {
        request(token(KEY, ISSUER, "grocery-api", "customer-1", Instant.now().plusSeconds(300)))
                .expectStatus().isOk();
    }

    @Test
    void rejectsWrongIssuerAudienceExpiredAndMissingIdentityOrExpiry() throws Exception {
        request(token(KEY, "https://other.example.test", "grocery-api", "customer-1", Instant.now().plusSeconds(300)))
                .expectStatus().isUnauthorized();
        request(token(KEY, ISSUER, "other-api", "customer-1", Instant.now().plusSeconds(300)))
                .expectStatus().isUnauthorized();
        request(token(KEY, ISSUER, "grocery-api", "customer-1", Instant.now().minusSeconds(300)))
                .expectStatus().isUnauthorized();
        request(token(KEY, ISSUER, "grocery-api", null, Instant.now().plusSeconds(300)))
                .expectStatus().isUnauthorized();
        request(token(KEY, ISSUER, "grocery-api", "customer-1", null))
                .expectStatus().isUnauthorized();
    }

    @Test
    void rejectsForgedSignatureAndMalformedBearer() throws Exception {
        RSAKey attacker = new RSAKeyGenerator(2048).keyID("test-key").generate();
        request(token(attacker, ISSUER, "grocery-api", "customer-1", Instant.now().plusSeconds(300)))
                .expectStatus().isUnauthorized();
        request("malformed").expectStatus().isUnauthorized();
    }

    @Test
    void rejectsUntrustedCorsOrigin() {
        client.options().uri("/api/customer/cart")
                .header("Origin", "https://attacker.example.test")
                .header("Access-Control-Request-Method", "GET")
                .exchange().expectStatus().isForbidden();
        client.options().uri("/api/customer/cart")
                .header("Origin", "https://shop.example.test")
                .header("Access-Control-Request-Method", "GET")
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "https://shop.example.test");
    }

    private WebTestClient.ResponseSpec request(String token) {
        return client.get().uri("/api/customer/cart").headers(headers -> headers.setBearerAuth(token)).exchange();
    }

    private String token(RSAKey key, String issuer, String audience, String subject, Instant expiry) throws Exception {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject);
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }
}
