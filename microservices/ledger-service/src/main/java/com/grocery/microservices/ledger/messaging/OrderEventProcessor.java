package com.grocery.microservices.ledger.messaging;

import com.grocery.microservices.ledger.event.OrderCreatedEvent;
import com.grocery.microservices.ledger.model.Ledger;
import com.grocery.microservices.ledger.model.ProcessedOrderEvent;
import com.grocery.microservices.ledger.port.LedgerProjectionUpdater;
import com.grocery.microservices.ledger.repository.ProcessedOrderEventRepository;
import com.grocery.microservices.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class OrderEventProcessor implements LedgerProjectionUpdater {
    private final LedgerRepository ledgerRepository;
    private final ProcessedOrderEventRepository processedEventRepository;

    public OrderEventProcessor(LedgerRepository ledgerRepository,
                               ProcessedOrderEventRepository processedEventRepository) {
        this.ledgerRepository = ledgerRepository;
        this.processedEventRepository = processedEventRepository;
    }

    @Override
    @Transactional
    public void process(OrderCreatedEvent event) {
        validate(event);
        if (processedEventRepository.existsById(event.eventId().toString())) {
            return;
        }
        if (ledgerRepository.findByOrderId(event.orderId()).isPresent()) {
            return;
        }
        Ledger ledger = new Ledger();
        ledger.setOrderId(event.orderId());
        ledger.setUserId(event.userId());
        ledger.setTotalAmount(BigDecimal.valueOf(event.total()));
        ledger.setCurrency(event.currency() == null ? "SEK" : event.currency());
        ledger.setItemCount(0);
        ledger.setCreatedAt(LocalDateTime.now());
        ledger.setDetails("Order created");
        ledgerRepository.save(ledger);

        ProcessedOrderEvent processed = new ProcessedOrderEvent();
        processed.setEventId(event.eventId().toString());
        processed.setProcessedAt(LocalDateTime.now());
        processedEventRepository.save(processed);
    }

    private void validate(OrderCreatedEvent event) {
        if (event == null || event.eventId() == null || event.orderId() == null
                || event.orderId() <= 0 || event.userId() == null || event.userId().isBlank()
                || event.cartId() == null || event.cartId() <= 0 || event.total() < 0
                || (event.currency() != null && !event.currency().matches("[A-Z]{3}"))) {
            throw new IllegalArgumentException("Invalid order-created event");
        }
    }
}
