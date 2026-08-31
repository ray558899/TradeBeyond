package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 建立訂單的請求。只接受 userId、productId、orderAmount ——
 * unitPrice/taxRate/totalCost 一律由後端查 DB 並計算，不接受前端傳入（CLAUDE.md Part 2.1）。
 */
public record OrderCreateRequest(
        @NotNull Long userId,
        @NotNull Long productId,
        @NotNull @Positive BigDecimal orderAmount
) {
}
