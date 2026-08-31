package com.tradebeyond.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 訂單。total_cost 一律由後端以 order_amount * unit_price_snapshot * (1 + tax_rate_snapshot) 計算，
 * unit_price_snapshot / tax_rate_snapshot 都是下單當下鎖住的快照值，之後（含 PATCH 重算）
 * 一律用快照值計算，不重新查 Product / ProductCategory，避免後續調價/調稅率讓舊訂單結果跑掉。
 * 資料表名稱為 orders（避開 SQL 保留字 order）。
 */
@Entity
@Table(name = "orders")
@Getter
// 有 @Version 的 Entity，Hibernate 會多綁一個 version 參數（軟刪除本身也要受樂觀鎖保護），
// 所以這裡的 WHERE 子句要多一個 "AND version = ?"，只有一個 order_id 佔位符會在 flush 時綁參數失敗。
@SQLDelete(sql = "UPDATE orders SET delete_at = now() WHERE order_id = ? AND version = ?")
@SQLRestriction("delete_at IS NULL")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Setter
    @Column(name = "order_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal orderAmount;

    @Setter
    @Column(name = "unit_price_snapshot", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPriceSnapshot;

    @Setter
    @Column(name = "tax_rate_snapshot", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRateSnapshot;

    @Setter
    @Column(name = "total_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalCost;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @Column(name = "delete_at")
    private Instant deleteAt;
}
