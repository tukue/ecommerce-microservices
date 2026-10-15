package com.grocery.microservices.cart.controller;

import com.grocery.microservices.cart.config.AuthenticatedCustomer;
import com.grocery.microservices.cart.dto.CartDTO;
import com.grocery.microservices.cart.dto.CartItemDTO;
import com.grocery.microservices.cart.dto.CartItemQuantityDTO;
import com.grocery.microservices.cart.service.CartService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/api/customer")
public class CartController {

    private final CartService cartService;
    private final String internalServiceKey;

    public CartController(CartService cartService,
                          @Value("${services.internal-key:}") String internalServiceKey) {
        this.cartService = cartService;
        this.internalServiceKey = internalServiceKey;
    }

    @PostMapping("/cart")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartDTO> createCart(@AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cartService.createCart(customer));
    }

    @GetMapping("/cart")
    @PreAuthorize("hasAuthority('SCOPE_cart:read')")
    public ResponseEntity<CartDTO> getCurrentCart(@AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.getCurrentCart(customer));
    }

    @GetMapping("/carts/{cartId}")
    @PreAuthorize("hasAuthority('SCOPE_cart:read')")
    public ResponseEntity<CartDTO> getCartById(@PathVariable Long cartId,
                                               @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.getCartById(cartId, customer));
    }

    @PostMapping("/cart/{cartId}/items")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartDTO> addItemToCart(@PathVariable Long cartId,
                                                 @Valid @RequestBody CartItemDTO itemDto,
                                                 @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.addItem(cartId, itemDto.getProductId(), itemDto.getQuantity(), customer));
    }

    @PatchMapping("/cart/{cartId}/items/{itemId}")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartDTO> updateItemQuantity(@PathVariable Long cartId,
                                                      @PathVariable Long itemId,
                                                      @Valid @RequestBody CartItemQuantityDTO quantityDto,
                                                      @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.updateItemQuantity(cartId, itemId, quantityDto.getQuantity(), customer));
    }

    @DeleteMapping("/cart/{cartId}/items/{itemId}")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartDTO> removeItemFromCart(@PathVariable Long cartId,
                                                      @PathVariable Long itemId,
                                                      @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.removeItem(cartId, itemId, customer));
    }

    @PostMapping("/cart/{cartId}/checkout")
    @PreAuthorize("hasAuthority('SCOPE_cart:write')")
    public ResponseEntity<CartDTO> markCartCheckedOut(@PathVariable Long cartId,
                                                       @AuthenticationPrincipal AuthenticatedCustomer customer) {
        return ResponseEntity.ok(cartService.markCheckedOut(cartId, customer));
    }

    @PostMapping("/internal/carts/{cartId}/reopen")
    public ResponseEntity<Void> reopenAfterPaymentExpiry(
            @PathVariable Long cartId,
            @RequestHeader(name = "X-Internal-Service-Key", required = false) String suppliedKey) {
        if (!isValidInternalKey(suppliedKey)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        cartService.reopenCartInternally(cartId);
        return ResponseEntity.noContent().build();
    }

    private boolean isValidInternalKey(String suppliedKey) {
        return internalServiceKey != null && !internalServiceKey.isBlank()
                && suppliedKey != null
                && MessageDigest.isEqual(internalServiceKey.getBytes(StandardCharsets.UTF_8),
                suppliedKey.getBytes(StandardCharsets.UTF_8));
    }
}
