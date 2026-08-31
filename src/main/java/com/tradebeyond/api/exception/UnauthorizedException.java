package com.tradebeyond.api.exception;

/**
 * 未授權，對應 HTTP 401。
 */
public abstract class UnauthorizedException extends BaseException {
    protected UnauthorizedException(String errorCode, String message) {
        super(errorCode, message);
    }
}
