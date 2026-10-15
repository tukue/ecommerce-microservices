package com.grocery.microservices.order.controller;

import com.grocery.microservices.order.dto.OrderDTO;
import com.grocery.microservices.order.dto.CheckoutRequest;
import com.grocery.microservices.order.dto.OrderLineDTO;
import com.grocery.microservices.order.config.AuthenticatedCustomer;
import com.grocery.microservices.order.model.Order;
import com.grocery.microservices.order.model.OrderLine;
import com.grocery.microservices.order.model.OrderStatus;
import com.grocery.microservices.order.service.OrderService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import java.util.Collections;

@RestController
@RequestMapping("/api/customer")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    @PreAuthorize("hasAuthority('SCOPE_order:write')")
    public ResponseEntity<OrderDTO> checkout(@Valid @RequestBody CheckoutRequest checkoutRequest,
                                             @AuthenticationPrincipal AuthenticatedCustomer customer,
                                             HttpServletRequest request) {
        Order createdOrder = orderService.checkout(
                checkoutRequest.getCartId(),
                resolveIdempotencyKey(request, checkoutRequest),
                request.getHeader("X-Correlation-Id"),
                customer,
                request.getHeader("Authorization"));
        return ResponseEntity.created(URI.create("/api/customer/orders/" + createdOrder.getId()))
                .body(convertToDto(createdOrder));
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('SCOPE_order:read')")
    public List<OrderDTO> getOrders(@AuthenticationPrincipal AuthenticatedCustomer customer) {
        return orderService.getOrdersForUser(customer).stream().map(this::convertToDto).toList();
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('SCOPE_order:read')")
    public OrderDTO getOrder(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedCustomer customer) {
        Order order = orderService.getOrder(id, customer);
        return convertToDto(order);
    }

    @PatchMapping("/orders/{id}/status")
    @PreAuthorize("hasAuthority('SCOPE_order:write')")
    public ResponseEntity<OrderDTO> updateStatus(@PathVariable Long id, @RequestParam OrderStatus status,
                                                 @AuthenticationPrincipal AuthenticatedCustomer customer) {
        Order updatedOrder = orderService.updateOrderStatus(id, status, customer);
        return ResponseEntity.ok(convertToDto(updatedOrder));
    }

    private OrderDTO convertToDto(Order order) {
        OrderDTO orderDto = new OrderDTO();
        orderDto.setId(order.getId());
        orderDto.setUserId(order.getUserId());
        orderDto.setCartId(order.getCartId());
        orderDto.setStatus(order.getStatus());
        orderDto.setOrderDate(order.getOrderDate());
        orderDto.setTotal(order.getTotal());
        orderDto.setCurrency(order.getCurrency());
        orderDto.setOrderLines(toOrderLineDtos(order.getOrderLines()));
        return orderDto;
    }

    private List<OrderLineDTO> toOrderLineDtos(List<OrderLine> orderLines) {
        if (orderLines == null) {
            return Collections.emptyList();
        }
        return orderLines.stream().map(orderLine -> {
            OrderLineDTO dto = new OrderLineDTO();
            dto.setProductId(orderLine.getProductId());
            dto.setProductName(orderLine.getProductName());
            dto.setUnitPrice(orderLine.getUnitPrice());
            dto.setQuantity(orderLine.getQuantity());
            dto.setLineTotal(orderLine.getLineTotal());
            return dto;
        }).toList();
    }

    private String resolveIdempotencyKey(HttpServletRequest request, CheckoutRequest checkoutRequest) {
        String headerKey = request.getHeader("Idempotency-Key");
        if (headerKey != null && !headerKey.isBlank()) {
            return headerKey;
        }
        return checkoutRequest.getIdempotencyKey();
    }
}