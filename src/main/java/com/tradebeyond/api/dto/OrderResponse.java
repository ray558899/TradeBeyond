package com.tradebeyond.api.dto;

import com.tradebeyond.api.entity.Order;
import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(
        Long orderId,
        Long userId,
        Long productId,
        BigDecimal orderAmount,
        BigDecimal unitPriceSnapshot,
        BigDecimal taxRateSnapshot,
        BigDecimal totalCost,
        Instant createAt,
        Instant updateAt
) {
    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getUser().getUserId(),
                order.getProduct().getProductId(),
                order.getOrderAmount(),
                order.getUnitPriceSnapshot(),
                order.getTaxRateSnapshot(),
                order.getTotalCost(),
                order.getCreateAt(),
                order.getUpdateAt()
        );
    }
}
