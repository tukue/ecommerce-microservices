package com.grocery.microservices.order.client;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestProductClientTest {

    private RestTemplate restTemplate;
    private RestProductClient client;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        client = new RestProductClient(restTemplate, new ObjectMapper(), "http://product-service:8080",
                "internal-test-key");
    }

    @Test
    void releaseBuildsEncodedEndpointForValidKey() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());

        client.release("checkout:customer-1:idem:101", "Bearer token");

        verify(restTemplate, times(1))
                .exchange(any(URI.class), org.mockito.ArgumentMatchers.eq(HttpMethod.DELETE),
                        any(HttpEntity.class), org.mockito.ArgumentMatchers.eq(Void.class));
    }

    @Test
    void releaseRejectsReservationKeysWithPathTraversalAttempt() {
        for (String evil : Arrays.asList("../../admin", "a/b", "a/path?x=1", "key#frag",
                "", "   ", "a".repeat(256), "../secret")) {
            assertThrows(IllegalArgumentException.class,
                    () -> client.release(evil, "Bearer token"),
                    "expected rejection for key: " + evil);
        }
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class),
                any(HttpEntity.class), any(Class.class));
    }

    @Test
    void releaseUsesEncodedReservationKeyInPath() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());

        client.release("checkout:customer-1:idem:101", "Bearer token");

        java.util.concurrent.atomic.AtomicReference<URI> captured = new java.util.concurrent.atomic.AtomicReference<>();
        verify(restTemplate, times(1)).exchange(
                org.mockito.ArgumentMatchers.<URI>argThat(uri -> {
                    captured.set(uri);
                    return true;
                }),
                org.mockito.ArgumentMatchers.eq(HttpMethod.DELETE), any(HttpEntity.class),
                org.mockito.ArgumentMatchers.eq(Void.class));

        assertThat(captured.get().getPath()).isEqualTo("/products/reservations/checkout:customer-1:idem:101");
    }

    @Test
    void releaseAfterPaymentExpiryUsesInternalServiceCredential() {
        when(restTemplate.exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(org.springframework.http.ResponseEntity.noContent().build());

        client.releaseAfterPaymentExpiry("checkout:customer-1:idem:101");

        java.util.concurrent.atomic.AtomicReference<HttpEntity> captured = new java.util.concurrent.atomic.AtomicReference<>();
        verify(restTemplate).exchange(any(URI.class), org.mockito.ArgumentMatchers.eq(HttpMethod.DELETE),
                org.mockito.ArgumentMatchers.<HttpEntity>argThat(entity -> {
                    captured.set(entity);
                    return true;
                }), org.mockito.ArgumentMatchers.eq(Void.class));
        assertThat(captured.get().getHeaders().getFirst("X-Internal-Service-Key"))
                .isEqualTo("internal-test-key");
    }
}