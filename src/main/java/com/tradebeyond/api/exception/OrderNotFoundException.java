package com.tradebeyond.api.exception;

public class OrderNotFoundException extends ResourceNotFoundException {
    public OrderNotFoundException(Long orderId) {
        super("ORDER_NOT_FOUND", "找不到 orderId=" + orderId + " 的訂單");
    }
}
