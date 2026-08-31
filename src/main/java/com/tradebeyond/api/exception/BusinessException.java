package com.tradebeyond.api.exception;

/**
 * 業務規則違反，對應 HTTP 400。
 */
public abstract class BusinessException extends BaseException {
    protected BusinessException(String errorCode, String message) {
        super(errorCode, message);
    }
}
