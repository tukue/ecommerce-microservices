package com.grocery.microservices.cart.controller;

import com.grocery.microservices.cart.config.AuthenticatedCustomer;
import com.grocery.microservices.cart.config.TestJwtSupport;
import com.grocery.microservices.cart.config.TestSecurityConfig;
import com.grocery.microservices.cart.dto.CartDTO;
import com.grocery.microservices.cart.dto.CartItemDTO;
import com.grocery.microservices.cart.dto.CartItemQuantityDTO;
import com.grocery.microservices.cart.exception.CartItemNotFoundException;
import com.grocery.microservices.cart.exception.CartNotFoundException;
import com.grocery.microservices.cart.exception.ProductNotFoundException;
import com.grocery.microservices.cart.exception.ProductUnavailableException;
import com.grocery.microservices.cart.exception.InsufficientProductStockException;
import com.grocery.microservices.cart.service.CartService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(CartController.class)
@Import(TestSecurityConfig.class)
public class CartControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CartService cartService;

    @Autowired
    private ObjectMapper objectMapper;

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    public void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/customer/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    public void rejectsRequestWhenTokenIsMissingScope() throws Exception {
        mockMvc.perform(get("/api/customer/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.tokenWithScopes("customer-1", "order:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    public void testGetCartById() throws Exception {
        CartDTO cart = new CartDTO();
        cart.setId(1L);

        when(cartService.getCartById(1L, new AuthenticatedCustomer("customer-1"))).thenReturn(cart);

        mockMvc.perform(get("/api/customer/carts/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void returnsNotFoundWhenOtherCustomersCartIsRequested() throws Exception {
        when(cartService.getCartById(1L, new AuthenticatedCustomer("customer-2")))
                .thenThrow(new CartNotFoundException(1L));

        mockMvc.perform(get("/api/customer/carts/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-2"))))
                .andExpect(status().isNotFound());
    }

    @Test
    public void testCreateCart() throws Exception {
        CartDTO cartDTO = new CartDTO();
        CartDTO returnedCart = new CartDTO();
        returnedCart.setId(1L);

        when(cartService.createCart(new AuthenticatedCustomer("customer-1"))).thenReturn(returnedCart);

        mockMvc.perform(post("/api/customer/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cartDTO)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void getsCurrentCartForAuthenticatedCustomer() throws Exception {
        CartDTO cart = new CartDTO();
        cart.setId(1L);
        when(cartService.getCurrentCart(new AuthenticatedCustomer("customer-1"))).thenReturn(cart);

        mockMvc.perform(get("/api/customer/cart")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void returnsNotFoundWhenRemovingMissingCartItem() throws Exception {
        when(cartService.removeItem(1L, 99L, new AuthenticatedCustomer("customer-1")))
                .thenThrow(new CartItemNotFoundException(1L, 99L));

        mockMvc.perform(delete("/api/customer/cart/1/items/99")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Cart item 99 was not found in cart 1"));
    }

    @Test
    public void addsCatalogProductToCart() throws Exception {
        CartItemDTO itemDTO = new CartItemDTO();
        itemDTO.setProductId(10L);
        itemDTO.setQuantity(2);
        CartDTO updatedCart = new CartDTO();
        updatedCart.setId(1L);

        when(cartService.addItem(1L, 10L, 2, new AuthenticatedCustomer("customer-1"))).thenReturn(updatedCart);

        mockMvc.perform(post("/api/customer/cart/1/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void returnsNotFoundWhenAddingMissingProduct() throws Exception {
        CartItemDTO itemDTO = new CartItemDTO();
        itemDTO.setProductId(99L);
        itemDTO.setQuantity(2);
        when(cartService.addItem(1L, 99L, 2, new AuthenticatedCustomer("customer-1")))
                .thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(post("/api/customer/cart/1/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemDTO)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Product 99 was not found"));
    }

    @Test
    public void returnsConflictWhenAddingUnavailableProduct() throws Exception {
        CartItemDTO itemDTO = new CartItemDTO();
        itemDTO.setProductId(10L);
        itemDTO.setQuantity(2);
        when(cartService.addItem(1L, 10L, 2, new AuthenticatedCustomer("customer-1")))
                .thenThrow(new ProductUnavailableException(10L));

        mockMvc.perform(post("/api/customer/cart/1/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemDTO)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Product 10 is out of stock"));
    }

    @Test
    public void returnsConflictWhenAddingQuantityAboveStock() throws Exception {
        CartItemDTO itemDTO = new CartItemDTO();
        itemDTO.setProductId(10L);
        itemDTO.setQuantity(2);
        when(cartService.addItem(1L, 10L, 2, new AuthenticatedCustomer("customer-1")))
                .thenThrow(new InsufficientProductStockException(10L, 3, 2));

        mockMvc.perform(post("/api/customer/cart/1/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemDTO)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Product 10 does not have enough stock. Requested: 3, Available: 2"));
    }

    @Test
    public void rejectsInvalidCatalogProductRequest() throws Exception {
        CartItemDTO itemDTO = new CartItemDTO();
        itemDTO.setProductId(10L);

        mockMvc.perform(post("/api/customer/cart/1/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(itemDTO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.quantity").value("Quantity must be at least 1"));

        verifyNoInteractions(cartService);
    }

    @Test
    public void updatesCartItemQuantity() throws Exception {
        CartItemQuantityDTO quantityDTO = new CartItemQuantityDTO();
        quantityDTO.setQuantity(3);
        CartDTO updatedCart = new CartDTO();
        updatedCart.setId(1L);

        when(cartService.updateItemQuantity(1L, 1L, 3, new AuthenticatedCustomer("customer-1"))).thenReturn(updatedCart);

        mockMvc.perform(patch("/api/customer/cart/1/items/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quantityDTO)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L));
    }

    @Test
    public void rejectsNonPositiveCartItemQuantity() throws Exception {
        CartItemQuantityDTO quantityDTO = new CartItemQuantityDTO();

        mockMvc.perform(patch("/api/customer/cart/1/items/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(quantityDTO)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.quantity").value("Quantity must be at least 1"));

        verifyNoInteractions(cartService);
    }

    @Test
    public void marksCartCheckedOut() throws Exception {
        CartDTO checkedOutCart = new CartDTO();
        checkedOutCart.setId(1L);
        checkedOutCart.setStatus("CHECKED_OUT");
        when(cartService.markCheckedOut(1L, new AuthenticatedCustomer("customer-1"))).thenReturn(checkedOutCart);

        mockMvc.perform(post("/api/customer/cart/1/checkout")
                        .header(HttpHeaders.AUTHORIZATION, bearer(TestJwtSupport.validToken("customer-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.status").value("CHECKED_OUT"));
    }

}
