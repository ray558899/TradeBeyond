package com.tradebeyond.api.exception;

/**
 * 請求本身合法，但與伺服器當下的資源狀態衝突，對應 HTTP 409。
 */
public abstract class ConflictException extends BaseException {
    protected ConflictException(String errorCode, String message) {
        super(errorCode, message);
    }
}
