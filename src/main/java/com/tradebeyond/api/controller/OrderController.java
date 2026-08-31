package com.tradebeyond.api.controller;

import com.tradebeyond.api.dto.OrderCreateRequest;
import com.tradebeyond.api.dto.OrderResponse;
import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.service.OrderService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/api/order")
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderCreateRequest request) {
        OrderResponse response = OrderResponse.from(orderService.createOrder(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/api/order/{orderId}")
    public OrderResponse patchOrder(@PathVariable Long orderId, @Valid @RequestBody OrderUpdateRequest request) {
        return OrderResponse.from(orderService.patchOrderAmount(orderId, request));
    }

    @DeleteMapping("/api/order/{orderId}")
    public ResponseEntity<Void> deleteOrder(@PathVariable Long orderId) {
        orderService.deleteOrder(orderId);
        return ResponseEntity.noContent().build();
    }

    // 注意：這裡跟上面 PATCH/DELETE 共用 "/api/order/{id}" 這個路徑樣式，
    // 但路徑變數語意不同（這裡是 userId，PATCH/DELETE 是 orderId）——
    // 這是題目原始規格 "GET /api/order/{userId}" 的字面要求。
    @GetMapping("/api/order/{userId}")
    public List<OrderResponse> listOrdersByUser(@PathVariable Long userId) {
        return orderService.findOrdersByUserId(userId).stream()
                .map(OrderResponse::from)
                .toList();
    }
}
