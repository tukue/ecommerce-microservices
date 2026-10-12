package com.grocery.microservices.gateway;

import com.grocery.microservices.gateway.config.TestSecurityConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Spring Cloud Gateway route definitions.
 *
 * <h3>What is tested</h3>
 * <ul>
 *   <li>Each route in {@link com.grocery.microservices.gateway.config.GatewayRoutesConfig}
 *       is exercised: the correct upstream and path are used, and required
 *       headers are forwarded.</li>
 *   <li>The {@link com.grocery.microservices.gateway.filter.CorrelationIdFilter}
 *       injects {@code X-Correlation-Id} on every request.</li>
 *   <li>Actuator health endpoint is reachable without authentication.</li>
 * </ul>
 *
 * <h3>What is NOT tested here</h3>
 * <ul>
 *   <li>JWT 401 enforcement — {@code SecurityConfig} is replaced by
 *       {@link TestSecurityConfig} (all requests permitted) so route tests
 *       are not coupled to JWT issuer availability. Test the 401 behaviour in
 *       a dedicated {@code SecurityConfigTest} slice with a mock JWKS server.</li>
 *   <li>Rate limiting — requires live Redis; test this in a Docker Compose
 *       smoke test or a separate {@code @Tag("integration")} suite.</li>
 * </ul>
 *
 * <h3>Acceptance criteria covered</h3>
 * AC-1, AC-7, AC-8, AC-9, AC-10
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class GatewayRoutesTest {

    // ── Mock upstreams — one per downstream service ───────────────────────────

    static final MockWebServer productServer = new MockWebServer();
    static final MockWebServer cartServer    = new MockWebServer();
    static final MockWebServer orderServer   = new MockWebServer();
    static final MockWebServer summaryServer = new MockWebServer();
    static final MockWebServer bffServer     = new MockWebServer();

    /**
     * Start mock servers and wire their dynamic ports into the Spring context
     * before any beans are initialised. Overrides stub URLs in
     * {@code application-test.properties}.
     */
    @DynamicPropertySource
    static void registerUpstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        productServer.start();
        cartServer.start();
        orderServer.start();
        summaryServer.start();
        bffServer.start();

        registry.add("gateway.services.product-url",
                () -> "http://localhost:" + productServer.getPort());
        registry.add("gateway.services.cart-url",
                () -> "http://localhost:" + cartServer.getPort());
        registry.add("gateway.services.order-url",
                () -> "http://localhost:" + orderServer.getPort());
        registry.add("gateway.services.summary-url",
                () -> "http://localhost:" + summaryServer.getPort());
        registry.add("gateway.services.bff-url",
                () -> "http://localhost:" + bffServer.getPort());
    }

    @AfterAll
    static void shutdownMockServers() throws IOException {
        productServer.shutdown();
        cartServer.shutdown();
        orderServer.shutdown();
        summaryServer.shutdown();
        bffServer.shutdown();
    }

    // ── WebTestClient ─────────────────────────────────────────────────────────

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void createClient() {
        client = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-7: Actuator health is public
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-7: GET /actuator/health returns 200 without authentication")
    void healthEndpointIsPublic() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-1: Catalog route is public; StripPrefix(2) removes /api/catalog
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-1: GET /api/catalog/products routes to product-service without auth")
    void catalogRouteForwardsToProductService() throws InterruptedException {
        productServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[{\"id\":1,\"name\":\"Apple\"}]"));

        client.get().uri("/api/catalog/products")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].name").isEqualTo("Apple");

        // Gateway must have stripped /api/catalog — upstream path is /products
        RecordedRequest recorded = productServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath())
                .as("StripPrefix(2) must remove /api/catalog from the path")
                .isEqualTo("/products");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // AC-8: CorrelationIdFilter injects X-Correlation-Id
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-8: X-Correlation-Id is injected on every forwarded request")
    void correlationIdIsInjectedOnForwardedRequest() throws InterruptedException {
        productServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[]"));

        client.get().uri("/api/catalog/products")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = productServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("X-Correlation-Id"))
                .as("CorrelationIdFilter must inject X-Correlation-Id on every request")
                .isNotNull()
                .isNotBlank();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Customer route preserves /api/customer for the controller mapping.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Cart route preserves /api/customer before forwarding")
    void cartRoutePreservesApiCustomerPrefix() throws InterruptedException {
        cartServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"id\":42,\"items\":[]}"));

        client.get().uri("/api/customer/cart")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = cartServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath())
                .as("CartController is mapped under /api/customer")
                .isEqualTo("/api/customer/cart");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Checkout route → order-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Checkout route: POST /api/customer/checkout preserves the controller path")
    void checkoutRouteForwardsToOrderService() throws InterruptedException {
        orderServer.enqueue(new MockResponse()
                .setResponseCode(201)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"orderId\":\"abc-123\",\"status\":\"PENDING\"}"));

        client.post().uri("/api/customer/checkout")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"cartId\":1,\"userId\":\"u-1\"}")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CREATED);

        RecordedRequest recorded = orderServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        assertThat(recorded.getPath())
                .as("OrderController is mapped under /api/customer")
                .isEqualTo("/api/customer/checkout");
    }

    @Test
    @DisplayName("Payment session route preserves the authenticated order path")
    void paymentSessionRouteForwardsToOrderService() throws InterruptedException {
        orderServer.enqueue(new MockResponse().setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"checkoutUrl\":\"https://checkout.stripe.com/session\"}"));

        client.post().uri("/api/customer/orders/42/payment-session")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = orderServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/api/customer/orders/42/payment-session");
    }

    @Test
    @DisplayName("Stripe webhook is public and forwarded to the signed webhook endpoint")
    void stripeWebhookForwardsToOrderService() throws InterruptedException {
        orderServer.enqueue(new MockResponse().setResponseCode(204));

        client.post().uri("/api/webhooks/stripe")
                .header("Stripe-Signature", "test-signature")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isNoContent();

        RecordedRequest recorded = orderServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath()).isEqualTo("/webhooks/stripe");
        assertThat(recorded.getHeader("Stripe-Signature")).isEqualTo("test-signature");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ledger route → ledger-service
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Ledger route preserves the customer API prefix")
    void ledgerRoutePreservesPrefix() throws InterruptedException {
        summaryServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"orderCount\":0,\"recentOrders\":[]}"));

        client.get().uri("/api/customer/ledger")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = summaryServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getPath())
                .as("LedgerController is mapped under /api/customer/ledger")
                .isEqualTo("/api/customer/ledger");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auth route → BFF (no prefix strip)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Auth login route: POST /api/auth/login forwards to BFF unchanged")
    void authLoginRouteForwardsToBffWithoutPrefixStrip() throws InterruptedException {
        bffServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"email\":\"user@example.com\",\"userId\":\"u-1\"}"));

        client.post().uri("/api/auth/login")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"username\":\"user\",\"password\":\"pass\"}")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = bffServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getMethod()).isEqualTo("POST");
        // Auth routes have no StripPrefix — path forwarded as-is
        assertThat(recorded.getPath())
                .as("Auth routes must not strip the path; BFF sees /api/auth/login")
                .isEqualTo("/api/auth/login");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // X-Gateway-Version header
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("X-Gateway-Version: 1.0 is added to service route requests")
    void gatewayVersionHeaderIsPresentOnServiceRoutes() throws InterruptedException {
        productServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("[]"));

        client.get().uri("/api/catalog/products")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest recorded = productServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(recorded).isNotNull();
        assertThat(recorded.getHeader("X-Gateway-Version"))
                .as("X-Gateway-Version must be added by the route filter")
                .isEqualTo("1.0");
    }
}
