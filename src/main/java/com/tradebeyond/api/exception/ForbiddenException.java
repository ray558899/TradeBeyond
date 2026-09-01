package com.tradebeyond.api.exception;

/**
 * 已驗證身分，但不允許存取這個資源，對應 HTTP 403（CLAUDE.md Part 2.4 的 IDOR 歸屬檢查）。
 * 跟 UnauthorizedException（401，根本沒有合法登入身分）不同：這裡是「知道你是誰，但這不是你的東西」。
 */
public abstract class ForbiddenException extends BaseException {
    protected ForbiddenException(String errorCode, String message) {
        super(errorCode, message);
    }
}
