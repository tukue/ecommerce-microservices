package com.grocery.microservices.product.controller;

import com.grocery.microservices.product.dto.ReserveStockRequest;
import com.grocery.microservices.product.dto.StockReservationDTO;
import com.grocery.microservices.product.model.StockReservation;
import com.grocery.microservices.product.service.StockReservationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/products")
public class StockReservationController {

    private final StockReservationService stockReservationService;
    private final String internalServiceKey;

    public StockReservationController(StockReservationService stockReservationService,
                                      @Value("${services.internal-key:}") String internalServiceKey) {
        this.stockReservationService = stockReservationService;
        this.internalServiceKey = internalServiceKey;
    }

    @PostMapping("/{productId}/reservations")
    public ResponseEntity<StockReservationDTO> reserve(@PathVariable Long productId,
                                       @Valid @RequestBody ReserveStockRequest request) {
        StockReservation reservation = stockReservationService.reserve(
                productId, request.getQuantity(), request.getReservationKey());
        return ResponseEntity.status(HttpStatus.CREATED).body(new StockReservationDTO(reservation));
    }

    @DeleteMapping("/reservations/{reservationKey}")
    public ResponseEntity<Void> release(@PathVariable String reservationKey) {
        stockReservationService.release(reservationKey);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/reservations/internal/{reservationKey}")
    public ResponseEntity<Void> releaseAfterPaymentExpiry(
            @PathVariable String reservationKey,
            @RequestHeader(name = "X-Internal-Service-Key", required = false) String suppliedKey) {
        if (internalServiceKey == null || internalServiceKey.isBlank() || suppliedKey == null
                || !MessageDigest.isEqual(internalServiceKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        stockReservationService.release(reservationKey);
        return ResponseEntity.noContent().build();
    }
}