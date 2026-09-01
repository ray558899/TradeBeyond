package com.tradebeyond.api.service;

import com.tradebeyond.api.config.CurrentUserProvider;
import com.tradebeyond.api.dto.OrderCreateRequest;
import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.ForbiddenAccessException;
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
        // Part 2.1/2.4：userId 不接受 request 帶入，一律用目前登入者身分建立，
        // client 端沒有任何欄位可以拿來冒用別人的身分下單。
        Users user = userService.getById(CurrentUserProvider.getCurrentUserId());
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

    @Transactional
    public Order patchOrderAmount(Long orderId, OrderUpdateRequest request) {
        Order order = getOwnedOrderOrThrow(orderId);
        BigDecimal totalCost = calculateTotalCost(
                request.orderAmount(), order.getUnitPriceSnapshot(), order.getTaxRateSnapshot());

        order.setOrderAmount(request.orderAmount());
        order.setTotalCost(totalCost);

        return order;
    }

    @Transactional
    public void deleteOrder(Long orderId) {
        Order order = getOwnedOrderOrThrow(orderId);
        orderRepository.delete(order);
    }

    @Transactional(readOnly = true)
    public List<Order> findOrdersByUserId(Long userId) {
        // Part 2.4 IDOR：先比對歸屬再查 DB，不是查出來後才判斷——被拒絕的請求不用多打一次
        // 不會用到結果的查詢，也不會因為「先查出使用者存不存在」而洩漏跟自己無關的帳號存在與否。
        if (!CurrentUserProvider.getCurrentUserId().equals(userId)) {
            throw new ForbiddenAccessException("只能查詢自己的訂單");
        }
        userService.getById(userId);
        return orderRepository.findByUserUserId(userId);
    }

    /**
     * Part 2.4 IDOR：orderId 跟目前登入者 userId 一起當查詢條件，訂單存在但不是自己的，
     * 跟訂單根本不存在，查出來都是空的——刻意讓兩種情況回應一模一樣的 404
     * （OrderNotFoundException），不讓呼叫方從回應差異反推出這個 orderId 是否真實存在。
     */
    private Order getOwnedOrderOrThrow(Long orderId) {
        Long currentUserId = CurrentUserProvider.getCurrentUserId();
        return orderRepository.findByOrderIdAndUserUserId(orderId, currentUserId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
    }

    private BigDecimal calculateTotalCost(BigDecimal orderAmount, BigDecimal unitPrice, BigDecimal taxRate) {
        return orderAmount
                .multiply(unitPrice)
                .multiply(BigDecimal.ONE.add(taxRate))
                .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
