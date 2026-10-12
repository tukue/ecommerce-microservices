package com.grocery.microservices.cart.service;

import com.grocery.microservices.cart.client.CatalogProduct;
import com.grocery.microservices.cart.client.ProductCatalogClient;
import com.grocery.microservices.cart.config.AuthenticatedCustomer;
import com.grocery.microservices.cart.dto.CartDTO;
import com.grocery.microservices.cart.dto.CartItemDTO;
import com.grocery.microservices.cart.exception.CartItemNotFoundException;
import com.grocery.microservices.cart.exception.CartNotFoundException;
import com.grocery.microservices.cart.exception.CartAlreadyCheckedOutException;
import com.grocery.microservices.cart.exception.ProductUnavailableException;
import com.grocery.microservices.cart.exception.InsufficientProductStockException;
import com.grocery.microservices.cart.exception.ProductCatalogUnavailableException;
import com.grocery.microservices.cart.exception.MixedCurrencyCartException;
import com.grocery.microservices.cart.model.Cart;
import com.grocery.microservices.cart.model.CartItem;
import com.grocery.microservices.cart.model.CartStatus;
import com.grocery.microservices.cart.repository.CartRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.stream.Collectors;

@Service
public class CartService {
    private static final Logger log = LoggerFactory.getLogger(CartService.class);
    private final CartRepository repo;
    private final ProductCatalogClient productCatalogClient;

    public CartService(CartRepository repo, ProductCatalogClient productCatalogClient) {
        this.repo = repo;
        this.productCatalogClient = productCatalogClient;
    }

    @Transactional
    @Retryable(retryFor = { SQLException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public CartDTO createCart(AuthenticatedCustomer customer) {
        Cart newCart = new Cart();
        newCart.setUserId(customer.customerId());
        Cart cart = repo.save(newCart);
        log.info("EVENT=CART_CREATED CART_ID={} CUSTOMER={}", cart.getId(), customer.customerId());
        return toDTO(cart);
    }

    @Transactional(readOnly = true)
    public CartDTO getCartById(Long id, AuthenticatedCustomer customer) {
        Cart cart = repo.findByIdAndUserId(id, customer.customerId())
                .orElseThrow(() -> new CartNotFoundException(id));
        return toDTO(cart);
    }

    @Transactional(readOnly = true)
    public CartDTO getCurrentCart(AuthenticatedCustomer customer) {
        Cart cart = repo.findFirstByUserIdAndStatusOrderByIdDesc(customer.customerId(), CartStatus.OPEN)
                .orElseThrow(() -> new CartNotFoundException("No open cart found for the authenticated customer"));
        return toDTO(cart);
    }

    @Transactional
    @Retryable(retryFor = { SQLException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public CartDTO addItem(Long cartId, Long productId, int quantity, AuthenticatedCustomer customer) {
        Cart cart = findOwnedCart(cartId, customer);
        assertOpen(cart);
        CatalogProduct product = productCatalogClient.getProduct(productId);
        if (!product.available()) {
            throw new ProductUnavailableException(productId);
        }
        if (product.currency() == null || !product.currency().matches("[A-Z]{3}")) {
            throw new ProductCatalogUnavailableException();
        }
        if (cart.getItems().isEmpty()) {
            cart.setCurrency(product.currency());
        } else if (!product.currency().equals(cart.getCurrency())) {
            throw new MixedCurrencyCartException();
        }
        int requestedQuantity = cart.getItems().stream()
                .filter(item -> productId.equals(item.getProductId()))
                .mapToInt(CartItem::getQuantity)
                .sum() + quantity;
        if (product.stockQuantity() < requestedQuantity) {
            throw new InsufficientProductStockException(productId, requestedQuantity, product.stockQuantity());
        }
        CartItem item = cart.getItems().stream()
                .filter(existing -> productId.equals(existing.getProductId()))
                .findFirst()
                .orElseGet(() -> {
                    CartItem newItem = new CartItem();
                    newItem.setProductId(product.id());
                    cart.getItems().add(newItem);
                    return newItem;
                });
        item.setProductName(product.name());
        item.setPrice(product.price());
        item.setQuantity(item.getQuantity() + quantity);
        Cart updatedCart = repo.save(cart);
        log.info("EVENT=ITEM_ADDED_TO_CART CART_ID={} PRODUCT={} QTY={}",
            cartId, item.getProductName(), item.getQuantity());
        return toDTO(updatedCart);
    }

    @Transactional
    @Retryable(retryFor = { SQLException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public CartDTO removeItem(Long cartId, Long itemId, AuthenticatedCustomer customer) {
        Cart cart = findOwnedCart(cartId, customer);
        assertOpen(cart);
        CartItem item = getCartItem(cart, cartId, itemId);
        cart.getItems().remove(item);
        if (cart.getItems().isEmpty()) {
            cart.setCurrency(null);
        }
        Cart updatedCart = repo.save(cart);
        log.info("EVENT=ITEM_REMOVED_FROM_CART CART_ID={} ITEM_ID={}", cartId, itemId);
        return toDTO(updatedCart);
    }

    @Transactional
    @Retryable(retryFor = { SQLException.class }, maxAttempts = 3, backoff = @Backoff(delay = 2000))
    public CartDTO updateItemQuantity(Long cartId, Long itemId, int quantity, AuthenticatedCustomer customer) {
        Cart cart = findOwnedCart(cartId, customer);
        assertOpen(cart);
        CartItem item = getCartItem(cart, cartId, itemId);
        item.setQuantity(quantity);
        Cart updatedCart = repo.save(cart);
        log.info("EVENT=CART_ITEM_QUANTITY_UPDATED CART_ID={} ITEM_ID={} QUANTITY={}", cartId, itemId, quantity);
        return toDTO(updatedCart);
    }

    @Transactional
    public CartDTO markCheckedOut(Long cartId, AuthenticatedCustomer customer) {
        Cart cart = findOwnedCart(cartId, customer);
        assertOpen(cart);
        cart.setStatus(CartStatus.CHECKED_OUT);
        Cart updatedCart = repo.save(cart);
        log.info("EVENT=CART_CHECKED_OUT CART_ID={} CUSTOMER={}", cartId, customer.customerId());
        return toDTO(updatedCart);
    }

    @Transactional
    public void reopenCartInternally(Long cartId) {
        Cart cart = repo.findById(cartId).orElseThrow(() -> new CartNotFoundException(cartId));
        if (cart.getStatus() == CartStatus.CHECKED_OUT) {
            cart.setStatus(CartStatus.OPEN);
            repo.save(cart);
            log.info("EVENT=CART_REOPENED_AFTER_PAYMENT_EXPIRY CART_ID={}", cartId);
        }
    }

    private Cart findOwnedCart(Long cartId, AuthenticatedCustomer customer) {
        return repo.findByIdAndUserId(cartId, customer.customerId())
                .orElseThrow(() -> new CartNotFoundException(cartId));
    }

    private CartDTO toDTO(Cart cart) {
        CartDTO dto = new CartDTO();
        dto.setId(cart.getId());
        dto.setStatus(cart.getStatus().name());
        dto.setCurrency(cart.getCurrency());
        if (cart.getItems() != null) {
            dto.setItems(cart.getItems().stream().map(this::toDTO).collect(Collectors.toList()));
        }
        return dto;
    }

    private CartItemDTO toDTO(CartItem item) {
        CartItemDTO dto = new CartItemDTO();
        dto.setId(item.getId());
        dto.setProductId(item.getProductId());
        dto.setProductName(item.getProductName());
        dto.setPrice(item.getPrice());
        dto.setQuantity(item.getQuantity());
        return dto;
    }

    private CartItem getCartItem(Cart cart, Long cartId, Long itemId) {
        return cart.getItems().stream()
                .filter(item -> itemId.equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new CartItemNotFoundException(cartId, itemId));
    }

    private void assertOpen(Cart cart) {
        if (cart.getStatus() == CartStatus.CHECKED_OUT) {
            throw new CartAlreadyCheckedOutException(cart.getId());
        }
    }

    @Transactional
    public Cart saveCart(Cart cart) {
        return repo.save(cart);
    }
}
