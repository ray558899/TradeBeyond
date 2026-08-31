package com.tradebeyond.api.dto;

import com.tradebeyond.api.entity.ProductCategory;
import java.math.BigDecimal;
import java.time.Instant;

public record ProductCategoryResponse(
        Long categoryId,
        String categoryName,
        BigDecimal taxRate,
        Instant createAt,
        Instant updateAt
) {
    public static ProductCategoryResponse from(ProductCategory category) {
        return new ProductCategoryResponse(
                category.getCategoryId(),
                category.getCategoryName(),
                category.getTaxRate(),
                category.getCreateAt(),
                category.getUpdateAt()
        );
    }
}
