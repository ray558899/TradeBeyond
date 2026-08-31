package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 商品的建立/更新請求，create 與 update 共用同一組欄位。
 */
public record ProductRequest(
        @NotNull Long productCategoryId,
        @NotNull @Positive BigDecimal unitPrice
) {
}
