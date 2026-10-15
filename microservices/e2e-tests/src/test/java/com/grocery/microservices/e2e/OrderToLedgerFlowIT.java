package com.grocery.microservices.e2e;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.grocery.microservices.order.OrderServiceApplication;
import com.grocery.microservices.order.service.OrderService;
import com.grocery.microservices.order.client.CartClient;
import com.grocery.microservices.order.client.CartItemSnapshot;
import com.grocery.microservices.order.client.CartSnapshot;
import com.grocery.microservices.order.client.ProductClient;
import com.grocery.microservices.order.client.ProductSnapshot;
import com.grocery.microservices.order.client.StockReservationSnapshot;
import com.grocery.microservices.order.exception.InsufficientProductStockException;
import com.grocery.microservices.order.exception.CheckoutCartAlreadyCheckedOutException;
import com.grocery.microservices.ledger.LedgerServiceApplication;
import com.grocery.microservices.ledger.dto.CustomerLedgerDTO;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.redpanda.RedpandaContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.awaitility.Awaitility.await;

class OrderToSummaryFlowIT {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:13");
    private static final DockerImageName REDPANDA_IMAGE =
            DockerImageName.parse("docker.redpanda.com/redpandadata/redpanda:v23.3.11");

    private static final int ORDER_PORT = 18081;
    private static final int SUMMARY_PORT = 18084;
    private static final String ORDER_BASE = "http://localhost:" + ORDER_PORT;
    private static final String SUMMARY_BASE = "http://localhost:" + SUMMARY_PORT;
    private static final String KAFKA_TOPIC = "order.created.v1";
    private static final String DLT_TOPIC = "order.created.v1.failed";

    private static PostgreSQLContainer orderDb;
    private static PostgreSQLContainer summaryDb;
    private static RedpandaContainer kafka;

    private static ConfigurableApplicationContext orderCtx;
    private static ConfigurableApplicationContext summaryCtx;

    private static final RestTemplate http = createNonThrowingRestTemplate();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RestTemplate createNonThrowingRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();
        restTemplate.setErrorHandler(new org.springframework.web.client.DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }
        });
        return restTemplate;
    }

    @BeforeAll
    static void startInfrastructureAndServices() throws Exception {
        Slf4jLogConsumer logConsumer = new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("docker"));

        orderDb = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("grocery")
                .withUsername("grocellery")
                .withPassword("grocellery");
        summaryDb = new PostgreSQLContainer(POSTGRES_IMAGE)
                .withDatabaseName("grocery")
                .withUsername("grocellery")
                .withPassword("grocellery");
        kafka = new RedpandaContainer(REDPANDA_IMAGE);

        orderDb.start();
        summaryDb.start();
        kafka.start();
        orderDb.followOutput(logConsumer);
        summaryDb.followOutput(logConsumer);
        kafka.followOutput(logConsumer);

        createTopic(KAFKA_TOPIC);
        createTopic(DLT_TOPIC);

        orderCtx = startOrderService();
        summaryCtx = startLedgerService();

        await().atMost(30, TimeUnit.SECONDS).until(() -> isUp(ORDER_BASE));
        await().atMost(30, TimeUnit.SECONDS).until(() -> isUp(SUMMARY_BASE));
    }

    @AfterAll
    static void stopAll() {
        if (summaryCtx != null) summaryCtx.close();
        if (orderCtx != null) orderCtx.close();
        if (kafka != null) kafka.stop();
        if (summaryDb != null) summaryDb.stop();
        if (orderDb != null) orderDb.stop();
    }

    @Test
    void verifiedPaymentPublishesEventConsumedByLedgerService() throws Exception {
        String orderToken = login(ORDER_BASE);
        String summaryToken = login(SUMMARY_BASE);

        String idempotencyKey = "e2e-happy-" + UUID.randomUUID();
        String correlationId = "corr-" + UUID.randomUUID();

        ResponseEntity<String> resp = orderHttp(orderToken, HttpMethod.POST, "/api/customer/checkout",
                Map.of("cartId", 1001L), idempotencyKey, correlationId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode root = MAPPER.readTree(resp.getBody());
        long orderId = root.get("id").asLong();
        double total = root.get("total").asDouble();
        assertThat(orderId).isPositive();
        assertThat(root.get("status").asText()).isEqualTo("PENDING");
        assertThat(total).isCloseTo(26.25, offset(0.01));

        orderCtx.getBean(OrderService.class).completePayment(orderId);
        awaitSummaryContains(summaryToken, orderId);
        CustomerLedgerDTO summary = getSummary(summaryToken);
        assertThat(summary.getOrderCount()).isGreaterThanOrEqualTo(1);
        assertThat(summary.getRecentOrders()).anyMatch(s -> s.getOrderId().equals(orderId));
        assertThat(summary.getTotalSpending()).isGreaterThanOrEqualTo(BigDecimal.valueOf(total));

        assertMetricsContain(orderToken, "outbox_events_published_total", 1.0);
    }

    @Test
    void replayWithSameIdempotencyKeyCreatesSingleOrderAndSummary() throws Exception {
        String orderToken = login(ORDER_BASE);
        String summaryToken = login(SUMMARY_BASE);
        String key = "e2e-idemp-" + UUID.randomUUID();
        String correlationId = "corr-idemp-" + UUID.randomUUID();

        long firstOrderId = checkout(orderToken, 2001, key, correlationId);
        long secondOrderId = checkout(orderToken, 2001, key, correlationId);
        assertThat(secondOrderId).isEqualTo(firstOrderId);

        awaitSummaryContains(summaryToken, firstOrderId);
        CustomerLedgerDTO summary = getSummary(summaryToken);
        long matchCount = summary.getRecentOrders().stream()
                .filter(s -> s.getOrderId().equals(firstOrderId))
                .count();
        assertThat(matchCount).isEqualTo(1);
    }

    @Test
    void duplicateEventDeliveryDoesNotDuplicateSummary() throws Exception {
        String orderToken = login(ORDER_BASE);
        String summaryToken = login(SUMMARY_BASE);
        String key = "e2e-dedup-" + UUID.randomUUID();
        String correlationId = "corr-dedup-" + UUID.randomUUID();

        long orderId = checkout(orderToken, 3001, key, correlationId);
        awaitSummaryContains(summaryToken, orderId);

        String payload = readOutboxPayload(orderId);
        assertThat(payload).isNotBlank();
        duplicatePublish(orderId, payload);

        await().atMost(8, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            CustomerLedgerDTO summary = getSummary(summaryToken);
            long matchCount = summary.getRecentOrders().stream()
                    .filter(s -> s.getOrderId().equals(orderId))
                    .count();
            assertThat(matchCount).isEqualTo(1);
        });

        assertMetricsContain(orderToken, "outbox_events_published_total", 1.0);
    }

    @Test
    void checkoutWithInsufficientStockReturnsConflictAndReleasesReservedLines() throws Exception {
        String orderToken = login(ORDER_BASE);
        String key = "e2e-stock-" + UUID.randomUUID();
        setStock(11L, 2);
        setStock(12L, 0);

        ResponseEntity<String> resp = orderHttp(orderToken, HttpMethod.POST, "/api/customer/checkout",
                Map.of("cartId", 4001L), key, "corr-stock-" + UUID.randomUUID());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(MAPPER.readTree(resp.getBody()).get("message").asText()).contains("insufficient stock");

        assertThat(stockOf(11L)).isEqualTo(2);
        assertThat(stockOf(12L)).isEqualTo(0);
    }

    @Test
    void checkoutConsumesReservedStockSuccessfully() throws Exception {
        String orderToken = login(ORDER_BASE);
        setStock(21L, 3);
        setStock(22L, 1);

        long orderId = checkout(orderToken, 5001, "e2e-consume-" + UUID.randomUUID(),
                "corr-consume-" + UUID.randomUUID());

        assertThat(orderId).isPositive();
        assertThat(stockOf(21L)).isEqualTo(1);
        assertThat(stockOf(22L)).isZero();
    }

    private long checkout(String token, long cartId, String idempotencyKey, String correlationId) throws Exception {
        ResponseEntity<String> resp = orderHttp(token, HttpMethod.POST, "/api/customer/checkout",
                Map.of("cartId", cartId), idempotencyKey, correlationId);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = MAPPER.readTree(resp.getBody()).get("id").asLong();
        orderCtx.getBean(OrderService.class).completePayment(orderId);
        return orderId;
    }

    private void awaitSummaryContains(String token, long orderId) {
        await().atMost(25, TimeUnit.SECONDS).pollInterval(Duration.ofSeconds(1)).until(() -> {
            try {
                CustomerLedgerDTO dto = getSummary(token);
                return dto != null && dto.getRecentOrders().stream().anyMatch(s -> s.getOrderId().equals(orderId));
            } catch (Exception e) {
                return false;
            }
        });
    }

    private CustomerLedgerDTO getSummary(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<CustomerLedgerDTO> resp = http.exchange(SUMMARY_BASE + "/api/customer/ledger",
                HttpMethod.GET, new HttpEntity<>(headers), CustomerLedgerDTO.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private ResponseEntity<String> orderHttp(String token, HttpMethod method, String path, Object body,
                                             String idempotencyKey, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        if (correlationId != null) headers.set("X-Correlation-Id", correlationId);
        return http.exchange(ORDER_BASE + path, method, new HttpEntity<>(body, headers), String.class);
    }

    private String login(String base) throws Exception {
        Map<String, String> req = Map.of("username", "demo-user", "password", "");
        ResponseEntity<String> resp = http.postForEntity(base + "/auth/login", req, String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return MAPPER.readTree(resp.getBody()).get("token").asText();
    }

    private String readOutboxPayload(long orderId) {
        DataSource ds = postgresDataSource(orderDb.getJdbcUrl(), orderDb.getUsername(), orderDb.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        return jdbc.queryForObject(
                "SELECT payload FROM order_event_store WHERE aggregate_id = ? AND event_type = 'OrderCreatedEvent' ORDER BY created_at DESC LIMIT 1",
                String.class, orderId);
    }

    private void duplicatePublish(long orderId, String payload) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(KAFKA_TOPIC, String.valueOf(orderId), payload)).get(15, TimeUnit.SECONDS);
        }
    }

    private static boolean isUp(String baseUrl) {
        try {
            ResponseEntity<String> resp = http.getForEntity(baseUrl + "/actuator/health", String.class);
            if (resp.getStatusCode().is2xxSuccessful()) {
                JsonNode status = MAPPER.readTree(resp.getBody()).path("status");
                return "UP".equals(status.asText());
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void assertMetricsContain(String token, String metricName, double expectedValue) {
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            ResponseEntity<String> resp = http.exchange(ORDER_BASE + "/actuator/prometheus", HttpMethod.GET,
                    new HttpEntity<>(headers), String.class);
            String body = resp.getBody();
            assertThat(body).contains(metricName);
            double value = Double.parseDouble(body.lines()
                    .filter(l -> l.startsWith(metricName + " ") || l.startsWith(metricName + "{"))
                    .map(l -> l.substring(l.lastIndexOf(' ') + 1).trim())
                    .findFirst()
                    .orElse("0"));
            assertThat(value).isGreaterThanOrEqualTo(expectedValue);
        });
    }

    private static void createTopic(String topic) throws Exception {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(props)) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(30, TimeUnit.SECONDS);
        }
    }

    private static ConfigurableApplicationContext startOrderService() {
        return new SpringApplicationBuilder(OrderServiceApplication.class)
                .sources(OrderSideStubs.class)
                .profiles("docker")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.config.name=order-e2e",
                        "--spring.profiles.active=docker",
                        "--server.port=" + ORDER_PORT,
                        "--spring.datasource.url=" + orderDb.getJdbcUrl(),
                        "--spring.datasource.username=" + orderDb.getUsername(),
                        "--spring.datasource.password=" + orderDb.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--security.jwt.issuer-uri=" + ORDER_BASE);
    }

    private static ConfigurableApplicationContext startLedgerService() {
        return new SpringApplicationBuilder(LedgerServiceApplication.class)
                .profiles("docker")
                .web(WebApplicationType.SERVLET)
                .run(
                        "--spring.config.name=ledger-e2e",
                        "--spring.profiles.active=docker",
                        "--server.port=" + SUMMARY_PORT,
                        "--spring.datasource.url=" + summaryDb.getJdbcUrl(),
                        "--spring.datasource.username=" + summaryDb.getUsername(),
                        "--spring.datasource.password=" + summaryDb.getPassword(),
                        "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                        "--security.jwt.issuer-uri=" + SUMMARY_BASE);
    }

    private static DriverManagerDataSource postgresDataSource(String url, String user, String pass) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);
        return ds;
    }

    private static int stockOf(Long productId) {
        return OrderSideStubs.productClient().stockOf(productId);
    }

    private static void setStock(Long productId, int quantity) {
        OrderSideStubs.productClient().setStock(productId, quantity);
    }

    @Configuration
    static class OrderSideStubs {

        private static StubProductClient PRODUCT_CLIENT;
        private static final java.util.Set<Long> CHECKED_OUT_CARTS = ConcurrentHashMap.newKeySet();

        @Bean
        @Primary
        CartClient cartClient() {
            return new CartClient() {
                @Override
                public CartSnapshot getCart(Long cartId, String auth) {
                    String status = CHECKED_OUT_CARTS.contains(cartId) ? "CHECKED_OUT" : "OPEN";
                    return switch (cartId.intValue()) {
                        case 4001 -> new CartSnapshot(cartId, status, List.of(
                                new CartItemSnapshot(201L, 11L, "Tea", 2.00, 2),
                                new CartItemSnapshot(202L, 12L, "Coffee", 3.00, 1)));
                        case 5001 -> new CartSnapshot(cartId, status, List.of(
                                new CartItemSnapshot(203L, 21L, "Rice", 1.50, 2),
                                new CartItemSnapshot(204L, 22L, "Salt", 0.75, 1)));
                        default -> new CartSnapshot(cartId, status, List.of(
                                new CartItemSnapshot(101L, 1L, "Organic Milk", 10.50, 2),
                                new CartItemSnapshot(102L, 2L, "Sourdough Bread", 5.25, 1)));
                    };
                }

                @Override
                public void markCheckedOut(Long cartId, String authorizationHeader) {
                    if (!CHECKED_OUT_CARTS.add(cartId)) {
                        throw new CheckoutCartAlreadyCheckedOutException(cartId);
                    }
                }

                @Override
                public void markOpen(Long cartId, String authorizationHeader) {
                    CHECKED_OUT_CARTS.remove(cartId);
                }
            };
        }

        @Bean
        @Primary
        static StubProductClient productClient() {
            if (PRODUCT_CLIENT == null) {
                PRODUCT_CLIENT = new StubProductClient();
            }
            return PRODUCT_CLIENT;
        }
    }

    static class StubProductClient implements ProductClient {
        private final Map<Long, Integer> stock = new ConcurrentHashMap<>();
        private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

        record Reservation(Long productId, int quantity) {
        }

        int stockOf(Long productId) {
            return stock.getOrDefault(productId, 100);
        }

        void setStock(Long productId, int quantity) {
            stock.put(productId, quantity);
        }

        @Override
        public ProductSnapshot getProduct(Long productId) {
            return new ProductSnapshot(productId, "product-" + productId, 9.99, true, stockOf(productId), null);
        }

        @Override
        public StockReservationSnapshot reserve(Long productId, int quantity, String reservationKey,
                                                String authorizationHeader) {
            int available = stockOf(productId);
            if (available < quantity) {
                throw new InsufficientProductStockException(productId, quantity, available);
            }
            stock.put(productId, available - quantity);
            reservations.put(reservationKey, new Reservation(productId, quantity));
            return new StockReservationSnapshot(reservationKey, productId, quantity, "ACTIVE");
        }

        @Override
        public void release(String reservationKey, String authorizationHeader) {
            Reservation reservation = reservations.remove(reservationKey);
            if (reservation != null) {
                stock.put(reservation.productId(),
                        stock.getOrDefault(reservation.productId(), 0) + reservation.quantity());
            }
        }

        @Override
        public void releaseAfterPaymentExpiry(String reservationKey) {
            release(reservationKey, null);
        }
    }
}
