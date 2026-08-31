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
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Refresh Token，存 DB 讓登出/撤銷可以直接標記 revoked_at 生效。
 * 不套用軟刪除三件套（create_at/update_at/delete_at）—— token 的生命週期是
 * 「過期」或「撤銷」，語意跟一般資源的軟刪除不同，revoked_at 已經足夠表達。
 */
@Entity
@Table(name = "refresh_token")
@Getter
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "refresh_token_id")
    private Long refreshTokenId;

    @Setter
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private Users user;

    // 存的是 TokenService.hashRefreshToken(...) 算出來的 SHA-256 雜湊值，絕不是明碼 token。
    @Setter
    @Column(name = "token", nullable = false, unique = true, length = 255)
    private String token;

    @Setter
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Setter
    @Column(name = "revoked_at")
    private Instant revokedAt;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;
}
