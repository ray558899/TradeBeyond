package com.tradebeyond.api.exception;

/**
 * 資源不存在，對應 HTTP 404。已軟刪除（delete_at IS NOT NULL）的資料視同不存在，一律套用此例外。
 */
public abstract class ResourceNotFoundException extends BaseException {
    protected ResourceNotFoundException(String errorCode, String message) {
        super(errorCode, message);
    }
}
