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
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 商品，unit_price 為單價，隸屬於一個 ProductCategory。
 */
@Entity
@Table(name = "product")
@Getter
@SQLDelete(sql = "UPDATE product SET delete_at = now() WHERE product_id = ?")
@SQLRestriction("delete_at IS NULL")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "product_id")
    private Long productId;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_category_id", nullable = false)
    private ProductCategory productCategory;

    @Setter
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @Column(name = "delete_at")
    private Instant deleteAt;
}
