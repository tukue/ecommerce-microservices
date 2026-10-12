package com.grocery.microservices.gateway;

import com.grocery.microservices.gateway.config.TestSecurityConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
class GatewayRoutesTest {
    static final MockWebServer productServer = new MockWebServer();
    static final MockWebServer cartServer = new MockWebServer();
    static final MockWebServer orderServer = new MockWebServer();
    static final MockWebServer summaryServer = new MockWebServer();
    static final MockWebServer bffServer = new MockWebServer();

    @DynamicPropertySource
    static void registerUpstreamUrls(DynamicPropertyRegistry registry) throws IOException {
        productServer.start();
        cartServer.start();
        orderServer.start();
        summaryServer.start();
        bffServer.start();
        registry.add("gateway.services.product-url", () -> url(productServer));
        registry.add("gateway.services.cart-url", () -> url(cartServer));
        registry.add("gateway.services.order-url", () -> url(orderServer));
        registry.add("gateway.services.summary-url", () -> url(summaryServer));
        registry.add("gateway.services.bff-url", () -> url(bffServer));
    }

    private static String url(MockWebServer server) {
        return "http://localhost:" + server.getPort();
    }

    @AfterAll
    static void shutdownMockServers() throws IOException {
        productServer.shutdown();
        cartServer.shutdown();
        orderServer.shutdown();
        summaryServer.shutdown();
        bffServer.shutdown();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client;

    @BeforeEach
    void createClient() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Test
    void healthEndpointIsPublic() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void catalogRouteStripsPublicPrefix() throws InterruptedException {
        productServer.enqueue(json(200, "[{\"id\":1,\"name\":\"Apple\"}]"));
        client.get().uri("/api/catalog/products")
                .exchange().expectStatus().isOk();
        assertThat(productServer.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/products");
    }

    @Test
    void catalogSearchRetainsQueryParameters() throws InterruptedException {
        productServer.enqueue(json(200, "[]"));
        client.get().uri("/api/catalog/products/search?name=apple")
                .exchange().expectStatus().isOk();
        assertThat(productServer.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/products/search?name=apple");
    }

    @Test
    void catalogWritesAndUndocumentedPathsNeverReachProductService() {
        int before = productServer.getRequestCount();
        client.post().uri("/api/catalog/products").exchange().expectStatus().isNotFound();
        client.get().uri("/api/catalog/admin").exchange().expectStatus().isNotFound();
        assertThat(productServer.getRequestCount()).isEqualTo(before);
    }

    @Test
    void correlationIdIsForwardedAndReturned() throws InterruptedException {
        productServer.enqueue(json(200, "[]"));
        client.get().uri("/api/catalog/products")
                .exchange().expectStatus().isOk().expectHeader().exists("X-Correlation-Id");
        RecordedRequest request = productServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request.getHeader("X-Correlation-Id")).isNotBlank();
    }

    @Test
    void cartRoutePreservesCustomerControllerPath() throws InterruptedException {
        cartServer.enqueue(json(200, "{\"id\":42,\"items\":[]}"));
        client.get().uri("/api/customer/cart").exchange().expectStatus().isOk();
        assertThat(cartServer.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/customer/cart");
    }

    @Test
    void checkoutRoutePreservesOrderControllerPath() throws InterruptedException {
        orderServer.enqueue(json(201, "{\"status\":\"PENDING\"}"));
        client.post().uri("/api/customer/checkout")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"cartId\":1}")
                .exchange().expectStatus().isEqualTo(HttpStatus.CREATED);
        assertThat(orderServer.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/customer/checkout");
    }

    @Test
    void paymentSessionRoutePreservesAuthenticatedOrderPath() throws InterruptedException {
        orderServer.enqueue(json(200, "{\"checkoutUrl\":\"https://checkout.stripe.com/session\"}"));
        client.post().uri("/api/customer/orders/42/payment-session")
                .exchange().expectStatus().isOk();
        assertThat(orderServer.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/customer/orders/42/payment-session");
    }

    @Test
    void stripeWebhookForwardsSignatureToOrderService() throws InterruptedException {
        orderServer.enqueue(new MockResponse().setResponseCode(204));
        client.post().uri("/api/webhooks/stripe")
                .header("Stripe-Signature", "test-signature")
                .bodyValue("{}")
                .exchange().expectStatus().isNoContent();
        RecordedRequest request = orderServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request.getPath()).isEqualTo("/webhooks/stripe");
        assertThat(request.getHeader("Stripe-Signature")).isEqualTo("test-signature");
    }

    @Test
    void ledgerReceiptRoutePreservesCustomerControllerPath() throws InterruptedException {
        summaryServer.enqueue(json(200, "{\"status\":\"ready\",\"content\":\"receipt\"}"));
        client.get().uri("/api/customer/ledger/orders/99/receipt")
                .exchange().expectStatus().isOk();
        assertThat(summaryServer.takeRequest(2, TimeUnit.SECONDS).getPath())
                .isEqualTo("/api/customer/ledger/orders/99/receipt");
    }

    @Test
    void authLoginRouteForwardsToBffUnchanged() throws InterruptedException {
        bffServer.enqueue(json(200, "{\"userId\":\"u-1\"}"));
        client.post().uri("/api/auth/login")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{}")
                .exchange().expectStatus().isOk();
        assertThat(bffServer.takeRequest(2, TimeUnit.SECONDS).getPath()).isEqualTo("/api/auth/login");
    }

    @Test
    void gatewayVersionHeaderIsAddedToCatalogRequests() throws InterruptedException {
        productServer.enqueue(json(200, "[]"));
        client.get().uri("/api/catalog/products").exchange().expectStatus().isOk();
        assertThat(productServer.takeRequest(2, TimeUnit.SECONDS).getHeader("X-Gateway-Version"))
                .isEqualTo("1.0");
    }

    private MockResponse json(int status, String body) {
        return new MockResponse().setResponseCode(status)
                .addHeader("Content-Type", "application/json")
                .setBody(body);
    }
}
