package com.tradebeyond.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 商品分類的建立/更新請求，create 與 update 共用同一組欄位。
 */
public record ProductCategoryRequest(
        @NotBlank String categoryName,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal taxRate
) {
}
