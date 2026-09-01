package com.tradebeyond.api.config;

import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 「目前登入者的 userId」統一從這裡拿——JwtAuthenticationFilter 驗證 token 成功後，
 * 直接把 userId 本身（Long）放進 Authentication 的 principal（不是 UserDetails
 * 物件，也不是帳號字串），這裡是唯一負責把它取出來的地方。Service 層的 IDOR
 * 歸屬檢查（CLAUDE.md Part 2.4）透過這裡拿到目前登入者 userId，不用另外發明
 * 一套機制，也跟 RateLimitFilter 讀 principal 的方式（見該檔案的 resolveKey）一致。
 */
public final class CurrentUserProvider {

    private CurrentUserProvider() {
    }

    public static Long getCurrentUserId() {
        return (Long) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
