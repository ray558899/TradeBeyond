package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * PATCH 訂單數量的請求。只能改 orderAmount；totalCost 由後端用
 * orderAmount * unitPriceSnapshot * (1 + taxRateSnapshot) 重算，
 * 不接受前端傳入 totalCost，也不重新查 DB 拿最新單價/稅率。
 */
public record OrderUpdateRequest(
        @NotNull @Positive BigDecimal orderAmount
) {
}
