package com.grocery.microservices.cart.service;

import com.grocery.microservices.cart.client.CatalogProduct;
import com.grocery.microservices.cart.client.ProductCatalogClient;
import com.grocery.microservices.cart.config.AuthenticatedCustomer;
import com.grocery.microservices.cart.exception.ProductCatalogUnavailableException;
import com.grocery.microservices.cart.exception.ProductNotFoundException;
import com.grocery.microservices.cart.exception.ProductUnavailableException;
import com.grocery.microservices.cart.exception.InsufficientProductStockException;
import com.grocery.microservices.cart.exception.MixedCurrencyCartException;
import com.grocery.microservices.cart.exception.CartAlreadyCheckedOutException;
import com.grocery.microservices.cart.model.Cart;
import com.grocery.microservices.cart.model.CartItem;
import com.grocery.microservices.cart.model.CartStatus;
import com.grocery.microservices.cart.exception.CartItemNotFoundException;
import com.grocery.microservices.cart.exception.CartNotFoundException;
import com.grocery.microservices.cart.repository.CartRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Optional;
import java.util.ArrayList;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
class CartServiceTest {
    private static final AuthenticatedCustomer CUSTOMER_1 = new AuthenticatedCustomer("customer-1");

    private CartRepository cartRepository;
    private ProductCatalogClient productCatalogClient;
    private CartService cartService;
    private Cart testCart;

    @BeforeEach
    void setUp() {
        cartRepository = Mockito.mock(CartRepository.class);
        productCatalogClient = Mockito.mock(ProductCatalogClient.class);
        cartService = new CartService(cartRepository, productCatalogClient);
        testCart = new Cart();
        testCart.setId(1L);
        testCart.setUserId("customer-1");
        testCart.setItems(new ArrayList<>());
    }

    @Test
    void testCreateCart() {
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);

        var createdCartDTO = cartService.createCart(CUSTOMER_1);

        assertNotNull(createdCartDTO);
        assertEquals(1L, createdCartDTO.getId());
        verify(cartRepository, times(1)).save(Mockito.any(Cart.class));
    }

    @Test
    void testGetCartById() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));

        var foundCartDTO = cartService.getCartById(1L, CUSTOMER_1);

        assertNotNull(foundCartDTO);
        assertEquals(1L, foundCartDTO.getId());
    }

    @Test
    void getCartByIdReturnsNotFoundWhenCartDoesNotBelongToTheCustomer() {
        when(cartRepository.findByIdAndUserId(2L, "customer-1")).thenReturn(Optional.empty());

        assertThrows(CartNotFoundException.class, () -> cartService.getCartById(2L, CUSTOMER_1));
    }

    @Test
    void getCurrentCartReturnsTheMostRecentlyCreatedCartForTheAuthenticatedCustomer() {
        when(cartRepository.findFirstByUserIdAndStatusOrderByIdDesc("customer-1", CartStatus.OPEN))
                .thenReturn(Optional.of(testCart));

        var foundCartDTO = cartService.getCurrentCart(CUSTOMER_1);

        assertEquals(1L, foundCartDTO.getId());
        verify(cartRepository).findFirstByUserIdAndStatusOrderByIdDesc("customer-1", CartStatus.OPEN);
    }

    @Test
    void getCurrentCartRejectsAnAuthenticatedCustomerWithoutACart() {
        when(cartRepository.findFirstByUserIdAndStatusOrderByIdDesc("customer-1", CartStatus.OPEN))
                .thenReturn(Optional.empty());

        assertThrows(CartNotFoundException.class, () -> cartService.getCurrentCart(CUSTOMER_1));
    }

    @Test
    void testAddItemToCart() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);
        when(productCatalogClient.getProduct(10L)).thenReturn(new CatalogProduct(10L, "Apple", 1.5, true, 10));

        var updatedCartDTO = cartService.addItem(1L, 10L, 2, CUSTOMER_1);

        assertNotNull(updatedCartDTO);
        assertEquals(1, updatedCartDTO.getItems().size());
        assertEquals(10L, updatedCartDTO.getItems().get(0).getProductId());
        assertEquals("Apple", updatedCartDTO.getItems().get(0).getProductName());
        assertEquals(1.5, updatedCartDTO.getItems().get(0).getPrice());
        assertEquals(2, updatedCartDTO.getItems().get(0).getQuantity());
        assertEquals("SEK", updatedCartDTO.getCurrency());
    }

    @Test
    void testAddItemRejectsDifferentCartCurrency() {
        CartItem existingItem = new CartItem();
        existingItem.setProductId(11L);
        existingItem.setQuantity(1);
        testCart.getItems().add(existingItem);
        testCart.setCurrency("SEK");
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(productCatalogClient.getProduct(10L))
                .thenReturn(new CatalogProduct(10L, "Apple", 1.5, "USD", true, 10));

        assertThrows(MixedCurrencyCartException.class, () ->
                cartService.addItem(1L, 10L, 1, CUSTOMER_1));
        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testAddItemMergesExistingProductLine() {
        CartItem existingItem = new CartItem();
        existingItem.setProductId(10L);
        existingItem.setQuantity(2);
        testCart.getItems().add(existingItem);
        testCart.setCurrency("SEK");
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);
        when(productCatalogClient.getProduct(10L))
                .thenReturn(new CatalogProduct(10L, "Apple", 1.5, true, 10));

        var updatedCartDTO = cartService.addItem(1L, 10L, 3, CUSTOMER_1);

        assertEquals(1, updatedCartDTO.getItems().size());
        assertEquals(5, updatedCartDTO.getItems().getFirst().getQuantity());
    }

    @Test
    void testAddItemRejectsMissingProduct() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(productCatalogClient.getProduct(99L)).thenThrow(new ProductNotFoundException(99L));

        assertThrows(ProductNotFoundException.class, () -> cartService.addItem(1L, 99L, 2, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testAddItemRejectsUnavailableProduct() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(productCatalogClient.getProduct(10L)).thenReturn(new CatalogProduct(10L, "Apple", 1.5, false, 10));

        assertThrows(ProductUnavailableException.class, () -> cartService.addItem(1L, 10L, 2, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testAddItemDoesNotPersistWhenCatalogIsUnavailable() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(productCatalogClient.getProduct(10L)).thenThrow(new ProductCatalogUnavailableException());

        assertThrows(ProductCatalogUnavailableException.class, () -> cartService.addItem(1L, 10L, 2, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testAddItemRejectsQuantityAboveAvailableStock() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        CartItem existingItem = new CartItem();
        existingItem.setProductId(10L);
        existingItem.setQuantity(1);
        testCart.getItems().add(existingItem);
        testCart.setCurrency("SEK");
        when(productCatalogClient.getProduct(10L)).thenReturn(new CatalogProduct(10L, "Apple", 1.5, true, 2));

        assertThrows(InsufficientProductStockException.class,
                () -> cartService.addItem(1L, 10L, 2, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testRemoveItemFromCart() {
        CartItem item = new CartItem();
        item.setId(1L);
        item.setProductName("Apple");
        item.setPrice(1.5);
        item.setQuantity(2);
        testCart.getItems().add(item);
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);

        var updatedCartDTO = cartService.removeItem(1L, 1L, CUSTOMER_1);

        assertNotNull(updatedCartDTO);
        verify(cartRepository, times(1)).save(Mockito.any(Cart.class));
    }

    @Test
    void testRemoveMissingItemFromCart() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));

        assertThrows(CartItemNotFoundException.class, () -> cartService.removeItem(1L, 99L, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testUpdateItemQuantity() {
        CartItem item = new CartItem();
        item.setId(1L);
        item.setQuantity(2);
        testCart.getItems().add(item);
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);

        var updatedCartDTO = cartService.updateItemQuantity(1L, 1L, 3, CUSTOMER_1);

        assertEquals(3, updatedCartDTO.getItems().get(0).getQuantity());
        verify(cartRepository).save(testCart);
    }

    @Test
    void testUpdateMissingItemQuantityDoesNotPersist() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));

        assertThrows(CartItemNotFoundException.class, () -> cartService.updateItemQuantity(1L, 99L, 3, CUSTOMER_1));

        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void marksOwnedCartAsCheckedOut() {
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var checkedOutCart = cartService.markCheckedOut(1L, CUSTOMER_1);

        assertEquals("CHECKED_OUT", checkedOutCart.getStatus());
        assertEquals(CartStatus.CHECKED_OUT, testCart.getStatus());
        verify(cartRepository).save(testCart);
    }

    @Test
    void reopensCheckedOutCartInternally() {
        testCart.setStatus(CartStatus.CHECKED_OUT);
        when(cartRepository.findById(1L)).thenReturn(Optional.of(testCart));
        when(cartRepository.save(Mockito.any(Cart.class))).thenAnswer(invocation -> invocation.getArgument(0));

        cartService.reopenCartInternally(1L);

        assertEquals(CartStatus.OPEN, testCart.getStatus());
        verify(cartRepository).save(testCart);
    }

    @Test
    void rejectsMutationAfterCartIsCheckedOut() {
        testCart.setStatus(CartStatus.CHECKED_OUT);
        when(cartRepository.findByIdAndUserId(1L, "customer-1")).thenReturn(Optional.of(testCart));

        assertThrows(CartAlreadyCheckedOutException.class,
                () -> cartService.addItem(1L, 10L, 1, CUSTOMER_1));

        verify(productCatalogClient, never()).getProduct(Mockito.anyLong());
        verify(cartRepository, never()).save(Mockito.any(Cart.class));
    }

    @Test
    void testSaveCart() {
        when(cartRepository.save(Mockito.any(Cart.class))).thenReturn(testCart);

        Cart savedCart = cartService.saveCart(testCart);

        assertNotNull(savedCart);
        assertEquals(1L, savedCart.getId());
        verify(cartRepository, times(1)).save(Mockito.any(Cart.class));
    }
}
