package com.tradebeyond.api.service;

import io.jsonwebtoken.Jwts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Access Token（JWT，短效期）與 Refresh Token（不透明亂數字串，交給 DB 存並可撤銷）的
 * 簽發/驗證邏輯。Refresh Token 刻意不用 JWT，因為 JWT 是無狀態的，簽出去就無法在到期前
 * 主動撤銷；Refresh Token 需要能被登出/撤銷，所以用「隨機字串 + DB 記錄」實作。
 */
@Service
public class TokenService {

    private static final long ACCESS_TOKEN_TTL_SECONDS = 15 * 60; // 15 分鐘
    private static final long REFRESH_TOKEN_TTL_SECONDS = 30L * 24 * 60 * 60; // 30 天
    private static final int REFRESH_TOKEN_RANDOM_BYTES = 32;

    private final SecretKey key;

    public TokenService(@Value("${app.jwt.secret}") String secret) {
        this.key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ACCESS_TOKEN_TTL_SECONDS)))
                .signWith(key)
                .compact();
    }

    public Long parseAccessToken(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return Long.valueOf(subject);
    }

    public String generateRefreshTokenValue() {
        byte[] randomBytes = new byte[REFRESH_TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    public long getAccessTokenExpirySeconds() {
        return ACCESS_TOKEN_TTL_SECONDS;
    }

    public long getRefreshTokenExpirySeconds() {
        return REFRESH_TOKEN_TTL_SECONDS;
    }

    /**
     * Refresh Token 是高熵亂數值，不是低熵的人類密碼，用 BCrypt 那種帶鹽、非決定性的雜湊反而讓
     * 「用使用者傳來的原始 token 查表」這件事做不到（每次雜湊結果都不同，查不到）。
     * 這裡要的是快速、決定性的雜湊，SHA-256 是正確的工具，跟 Users.password 的 BCrypt 要求是兩回事。
     */
    public String hashRefreshToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashBytes);
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JVM 保證內建的演算法，理論上不會發生
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
