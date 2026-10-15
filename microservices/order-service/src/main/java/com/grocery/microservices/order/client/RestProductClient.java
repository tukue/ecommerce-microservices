package com.grocery.microservices.order.client;

import com.grocery.microservices.order.exception.InsufficientProductStockException;
import com.grocery.microservices.order.exception.ProductServiceUnavailableException;
import com.grocery.microservices.order.exception.ProductUnavailableException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Component
public class RestProductClient implements ProductClient {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String productServiceBaseUrl;
    private final String internalServiceKey;

    public RestProductClient(RestTemplate restTemplate,
                             ObjectMapper objectMapper,
                             @Value("${services.product.base-url:http://product-service:8080}") String productServiceBaseUrl,
                             @Value("${services.internal-key:}") String internalServiceKey) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.internalServiceKey = internalServiceKey;
        URI serviceUri = URI.create(productServiceBaseUrl);
        if (serviceUri.getHost() == null || serviceUri.getUserInfo() != null
                || serviceUri.getQuery() != null || serviceUri.getFragment() != null
                || !("http".equals(serviceUri.getScheme()) || "https".equals(serviceUri.getScheme()))) {
            throw new IllegalArgumentException("Product service base URL must be an HTTP(S) host URL");
        }
        this.productServiceBaseUrl = serviceUri.toString();
    }

    @Override
    public ProductSnapshot getProduct(Long productId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(productServiceBaseUrl)
                    .pathSegment("products", String.valueOf(productId))
                    .build()
                    .encode()
                    .toUri();
            ProductSnapshot product = restTemplate.getForObject(uri, ProductSnapshot.class);
            if (product == null || !productId.equals(product.id()) || product.name() == null
                    || product.name().isBlank() || product.price() <= 0 || product.stockQuantity() < 0) {
                throw new ProductServiceUnavailableException();
            }
            return product;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new ProductUnavailableException(productId);
        } catch (RestClientException ex) {
            throw new ProductServiceUnavailableException();
        }
    }

    @Override
    public StockReservationSnapshot reserve(Long productId, int quantity, String reservationKey,
                                            String authorizationHeader) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(productServiceBaseUrl)
                    .pathSegment("products", String.valueOf(productId), "reservations")
                    .build()
                    .encode()
                    .toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            if (authorizationHeader != null && !authorizationHeader.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
            }
            ReserveRequest request = new ReserveRequest(reservationKey, quantity);
            ResponseEntity<JsonNode> response = restTemplate.exchange(uri, HttpMethod.POST,
                    new HttpEntity<>(request, headers), JsonNode.class);
            JsonNode body = response.getBody();
            if (body == null || body.path("reservationKey").asText(null) == null) {
                throw new ProductServiceUnavailableException();
            }
            return new StockReservationSnapshot(
                    body.path("reservationKey").asText(),
                    body.path("productId").asLong(),
                    body.path("quantity").asInt(),
                    body.path("status").asText());
        } catch (HttpStatusCodeException ex) {
            String detail = extractMessage(ex);
            if (ex.getStatusCode().value() == 409) {
                throw new InsufficientProductStockException(productId, quantity, detail);
            }
            if (ex.getStatusCode().value() == 404) {
                throw new ProductUnavailableException(productId);
            }
            throw new ProductServiceUnavailableException();
        } catch (RestClientException ex) {
            throw new ProductServiceUnavailableException();
        }
    }

    @Override
    public void release(String reservationKey, String authorizationHeader) {
        try {
            validateReservationKey(reservationKey);
            URI uri = UriComponentsBuilder.fromUriString(productServiceBaseUrl)
                    .pathSegment("products", "reservations", reservationKey)
                    .build()
                    .encode()
                    .toUri();
            HttpHeaders headers = new HttpHeaders();
            if (authorizationHeader != null && !authorizationHeader.isBlank()) {
                headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
            }
            restTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (RestClientException ex) {
            throw new ProductServiceUnavailableException();
        }
    }

    @Override
    public void releaseAfterPaymentExpiry(String reservationKey) {
        if (internalServiceKey == null || internalServiceKey.isBlank()) {
            throw new ProductServiceUnavailableException();
        }
        try {
            validateReservationKey(reservationKey);
            URI uri = UriComponentsBuilder.fromUriString(productServiceBaseUrl)
                    .pathSegment("products", "reservations", "internal", reservationKey)
                    .build()
                    .encode()
                    .toUri();
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Internal-Service-Key", internalServiceKey);
            restTemplate.exchange(uri, HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (RestClientException ex) {
            throw new ProductServiceUnavailableException();
        }
    }

    private void validateReservationKey(String reservationKey) {
        if (reservationKey == null || reservationKey.isBlank() || reservationKey.length() > 255
                || !reservationKey.matches("[A-Za-z0-9:_\\-.]*")) {
            throw new IllegalArgumentException("Invalid reservation key");
        }
    }

    private String extractMessage(HttpStatusCodeException ex) {
        try {
            JsonNode body = objectMapper.readTree(ex.getResponseBodyAsString());
            String message = body.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                return message;
            }
        } catch (Exception ignored) {
            // fall through to the alternative constructor below
        }
        return "insufficient stock (requested " + ex.getMessage() + ")";
    }

    private record ReserveRequest(String reservationKey, int quantity) {
    }
}