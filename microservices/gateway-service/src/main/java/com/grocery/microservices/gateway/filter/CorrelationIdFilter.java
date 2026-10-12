package com.grocery.microservices.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Global filter that ensures every request passing through the gateway carries a
 * unique {@code X-Correlation-Id} header, and that the same ID is echoed back
 * on the response.
 *
 * <h3>Behaviour</h3>
 * <ul>
 *   <li>If the inbound request already has an {@code X-Correlation-Id} header
 *       (e.g., set by an upstream load balancer or a calling service), the
 *       existing value is preserved and forwarded — it is not overwritten.</li>
 *   <li>If the header is absent, a new UUID is generated and injected into the
 *       forwarded request.</li>
 *   <li>The resolved correlation ID is always echoed on the response so clients
 *       and monitoring tools can correlate requests end-to-end.</li>
 * </ul>
 *
 * <h3>Ordering</h3>
 * {@link Ordered#HIGHEST_PRECEDENCE} + 1 ensures this filter runs before any
 * other filter that might read the correlation ID (e.g., an access log filter).
 *
 * <h3>Portfolio note</h3>
 * Using a {@link GlobalFilter} rather than {@code AddRequestHeader} in the route
 * definition is the correct approach for per-request dynamic values. A static
 * {@code AddRequestHeader} call is evaluated once at context startup and produces
 * the same value for every request.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Preserve existing correlation ID if already present (honour upstream tracing)
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        final String resolvedId = correlationId;

        // Mutate the forwarded request to include the correlation ID
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, resolvedId)
                .build();

        // Echo the correlation ID on the response so clients can trace the call
        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        mutatedExchange.getResponse().beforeCommit(() -> {
            mutatedExchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, resolvedId);
            return Mono.empty();
        });
        return chain.filter(mutatedExchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
