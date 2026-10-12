package com.grocery.microservices.cart.client;

import com.grocery.microservices.cart.exception.ProductCatalogUnavailableException;
import com.grocery.microservices.cart.exception.ProductNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Component
public class RestProductCatalogClient implements ProductCatalogClient {
    private final RestTemplate restTemplate;

    public RestProductCatalogClient(
            RestTemplateBuilder builder,
            @Value("${services.product.base-url:http://product-service:8080}") String productServiceBaseUrl,
            @Value("${services.product.connect-timeout:2s}") Duration connectTimeout,
            @Value("${services.product.read-timeout:2s}") Duration readTimeout) {
        this.restTemplate = builder
                .rootUri(productServiceBaseUrl)
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    @Override
    public CatalogProduct getProduct(Long productId) {
        try {
            CatalogProduct product = restTemplate.getForObject("/products/{productId}", CatalogProduct.class, productId);
            if (product == null || !productId.equals(product.id()) || product.name() == null
                    || product.name().isBlank() || product.price() <= 0 || product.currency() == null
                    || !product.currency().matches("[A-Z]{3}") || product.available() == null) {
                throw new ProductCatalogUnavailableException();
            }
            return product;
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.NOT_FOUND) {
                throw new ProductNotFoundException(productId);
            }
            throw new ProductCatalogUnavailableException();
        } catch (ResourceAccessException ex) {
            throw new ProductCatalogUnavailableException();
        } catch (RestClientException ex) {
            throw new ProductCatalogUnavailableException();
        }
    }
}
