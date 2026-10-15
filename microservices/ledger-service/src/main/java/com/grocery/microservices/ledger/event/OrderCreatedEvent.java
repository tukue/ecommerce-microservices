package com.grocery.microservices.ledger.event;

import java.time.Instant;
import java.util.UUID;

public record OrderCreatedEvent(UUID eventId, String eventType, String eventVersion, Instant occurredAt,
                                String producer, Long orderId, String userId, Long cartId, double total,
                                String correlationId, String currency) {
    public static final String TYPE = "OrderCreatedEvent";
    public static final String EVENT_VERSION = "1";
    public static final String PRODUCER = "order-service";
    public static final String DEFAULT_CURRENCY = "SEK";

    public OrderCreatedEvent(UUID eventId, Instant occurredAt, Long orderId, String userId, Long cartId, double total) {
        this(eventId, TYPE, EVENT_VERSION, occurredAt, PRODUCER, orderId, userId, cartId, total,
                eventId.toString(), DEFAULT_CURRENCY);
    }

    public OrderCreatedEvent(UUID eventId, Instant occurredAt, Long orderId, String userId, Long cartId, double total,
                             String correlationId) {
        this(eventId, TYPE, EVENT_VERSION, occurredAt, PRODUCER, orderId, userId, cartId, total,
                correlationId, DEFAULT_CURRENCY);
    }

    public OrderCreatedEvent(UUID eventId, Instant occurredAt, Long orderId, String userId, Long cartId, double total,
                             String correlationId, String currency) {
        this(eventId, TYPE, EVENT_VERSION, occurredAt, PRODUCER, orderId, userId, cartId, total,
                correlationId, currency);
    }
}
