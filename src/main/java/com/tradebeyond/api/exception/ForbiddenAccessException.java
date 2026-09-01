package com.tradebeyond.api.exception;

/**
 * IDOR 歸屬檢查失敗：目前登入者嘗試存取/操作不屬於自己的資源（CLAUDE.md Part 2.4）。
 * GET /api/order/{userId} 跟 DELETE /api/user/{userId} 這兩種「路徑參數的 userId
 * 跟目前登入者不一致」的情境共用這一個類別——訊息內容依呼叫端描述實際情境即可。
 */
public class ForbiddenAccessException extends ForbiddenException {
    public ForbiddenAccessException(String message) {
        super("FORBIDDEN_NOT_OWNER", message);
    }
}
