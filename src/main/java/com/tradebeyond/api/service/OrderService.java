package com.tradebeyond.api.service;

import com.tradebeyond.api.dto.OrderCreateRequest;
import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.OrderNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final int MONEY_SCALE = 4;

    private final OrderRepository orderRepository;
    private final ProductService productService;
    private final UserService userService;

    public OrderService(OrderRepository orderRepository, ProductService productService, UserService userService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
        this.userService = userService;
    }

    @Transactional
    public Order createOrder(OrderCreateRequest request) {
        Users user = userService.getById(request.userId());
        Product product = productService.getById(request.productId());

        BigDecimal unitPriceSnapshot = product.getUnitPrice();
        BigDecimal taxRateSnapshot = product.getProductCategory().getTaxRate();
        BigDecimal totalCost = calculateTotalCost(request.orderAmount(), unitPriceSnapshot, taxRateSnapshot);

        Order order = new Order();
        order.setUser(user);
        order.setProduct(product);
        order.setOrderAmount(request.orderAmount());
        order.setUnitPriceSnapshot(unitPriceSnapshot);
        order.setTaxRateSnapshot(taxRateSnapshot);
        order.setTotalCost(totalCost);

        return orderRepository.save(order);
    }

    @Transactional(readOnly = true)
    public Order getById(Long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    @Transactional
    public Order patchOrderAmount(Long orderId, OrderUpdateRequest request) {
        Order order = getById(orderId);
        BigDecimal totalCost = calculateTotalCost(
                request.orderAmount(), order.getUnitPriceSnapshot(), order.getTaxRateSnapshot());

        order.setOrderAmount(request.orderAmount());
        order.setTotalCost(totalCost);

        return order;
    }

    @Transactional
    public void deleteOrder(Long orderId) {
        Order order = getById(orderId);
        orderRepository.delete(order);
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersByUserId(Long userId) {
        userService.getById(userId);
        return orderRepository.findByUserUserId(userId);
    }

    private BigDecimal calculateTotalCost(BigDecimal orderAmount, BigDecimal unitPrice, BigDecimal taxRate) {
        return orderAmount
                .multiply(unitPrice)
                .multiply(BigDecimal.ONE.add(taxRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
