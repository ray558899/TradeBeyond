package com.tradebeyond.api.dto;

import com.tradebeyond.api.entity.Product;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductResponse(
        Long productId,
        Long productCategoryId,
        BigDecimal unitPrice,
        Instant createAt,
        Instant updateAt
) {
    public static ProductResponse from(Product product) {
        return new ProductResponse(
                product.getProductId(),
                product.getProductCategory().getCategoryId(),
                product.getUnitPrice(),
                product.getCreateAt(),
                product.getUpdateAt()
        );
    }
}
