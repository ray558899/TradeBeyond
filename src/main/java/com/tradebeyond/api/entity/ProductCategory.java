package com.tradebeyond.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 商品分類，tax_rate 為該分類的稅率（如 0.0500 = 5%）。
 */
@Entity
@Table(name = "product_category")
@Getter
@SQLDelete(sql = "UPDATE product_category SET delete_at = now() WHERE category_id = ?")
@SQLRestriction("delete_at IS NULL")
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long categoryId;

    @Setter
    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Setter
    @Column(name = "tax_rate", nullable = false, precision = 5, scale = 4)
    private BigDecimal taxRate;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @Column(name = "delete_at")
    private Instant deleteAt;
}
