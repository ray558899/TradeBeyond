package com.tradebeyond.api.exception;

public class ProductNotFoundException extends ResourceNotFoundException {
    public ProductNotFoundException(Long productId) {
        super("PRODUCT_NOT_FOUND", "找不到 productId=" + productId + " 的商品");
    }
}
