package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    private final TokenService tokenService =
            new TokenService("test-secret-key-must-be-at-least-32-bytes-long!!");

    @Test
    void generateAccessToken_producesTokenThatParsesBackToSameUserId() {
        String token = tokenService.generateAccessToken(42L);

        assertThat(tokenService.parseAccessToken(token)).isEqualTo(42L);
    }

    @Test
    void parseAccessToken_throwsJwtException_whenTokenIsMalformed() {
        // 沒帶 token、或帶了亂七八糟的字串，都要被拒絕，不能讓 filter 誤判成合法登入
        assertThatThrownBy(() -> tokenService.parseAccessToken("not-a-valid-token"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseAccessToken_throwsJwtException_whenSignedWithDifferentSecret() {
        // 用不同密鑰簽出來的 token 一定要驗證失敗，這是 JWT 簽章機制存在的意義
        TokenService otherTokenService = new TokenService("a-completely-different-secret-key-32-bytes!!");
        String tokenFromOtherService = otherTokenService.generateAccessToken(1L);

        assertThatThrownBy(() -> tokenService.parseAccessToken(tokenFromOtherService))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void generateRefreshTokenValue_producesDifferentRandomValues_eachTime() {
        String a = tokenService.generateRefreshTokenValue();
        String b = tokenService.generateRefreshTokenValue();

        assertThat(a).isNotBlank();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashRefreshToken_isDeterministic_forSameRawToken() {
        // 同一個原始 token 重複算雜湊必須得到一模一樣的結果，
        // 這樣「用使用者傳來的原始 token 算雜湊、拿去查 DB」這個查表流程才會一致成功
        String rawToken = tokenService.generateRefreshTokenValue();

        String hash1 = tokenService.hashRefreshToken(rawToken);
        String hash2 = tokenService.hashRefreshToken(rawToken);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void hashRefreshToken_matchesStoredHash_whenGivenTheOriginalRawToken() {
        // 模擬「登入時存雜湊進 DB → 之後查表時用使用者傳來的原始 token 重算雜湊去比對」這個完整流程
        String rawTokenIssuedToClient = tokenService.generateRefreshTokenValue();
        String hashStoredInDb = tokenService.hashRefreshToken(rawTokenIssuedToClient);

        String hashRecalculatedFromClientRequest = tokenService.hashRefreshToken(rawTokenIssuedToClient);

        assertThat(hashRecalculatedFromClientRequest).isEqualTo(hashStoredInDb);
    }

    @Test
    void hashRefreshToken_producesDifferentHash_forDifferentRawTokens() {
        String hashA = tokenService.hashRefreshToken("raw-token-a");
        String hashB = tokenService.hashRefreshToken("raw-token-b");

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void hashRefreshToken_neverReturnsThePlaintextValue() {
        // 存進 DB 的絕對不能是明碼本身
        String rawToken = "raw-token-value";

        assertThat(tokenService.hashRefreshToken(rawToken)).isNotEqualTo(rawToken);
    }
}
