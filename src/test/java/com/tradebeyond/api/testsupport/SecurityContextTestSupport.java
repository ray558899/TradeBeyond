package com.tradebeyond.api.testsupport;

import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 測試專用小工具：模擬 JwtAuthenticationFilter 驗證成功後在 SecurityContext 裡設定的
 * Authentication（principal 直接是 userId 本身）。不經過真實 HTTP/filter chain 的
 * Service 層單元測試/整合測試，靠這個模擬「目前登入者是誰」，才能測到 IDOR 歸屬檢查
 * （CLAUDE.md Part 2.4）依賴的 CurrentUserProvider。
 */
public final class SecurityContextTestSupport {

    private SecurityContextTestSupport() {
    }

    public static void authenticateAs(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
