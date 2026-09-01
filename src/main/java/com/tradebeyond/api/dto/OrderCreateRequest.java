package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * 建立訂單的請求。只接受 productId、orderAmount ——
 * unitPrice/taxRate/totalCost 一律由後端查 DB 並計算，不接受前端傳入（CLAUDE.md Part 2.1）。
 * 刻意沒有 userId 欄位：訂單一律歸屬到目前登入者（OrderService 用 CurrentUserProvider 取得），
 * client 端沒有任何欄位可以拿來冒用別人的身分建立訂單（Part 2.1/2.4）。
 */
public record OrderCreateRequest(
        @NotNull Long productId,
        @NotNull @Positive BigDecimal orderAmount
) {
}
