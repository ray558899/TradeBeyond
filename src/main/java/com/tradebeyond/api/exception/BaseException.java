package com.tradebeyond.api.exception;

/**
 * 例外階層的根類別，統一由 @ControllerAdvice 攔截並轉成 RFC 7807 Problem Details（Phase 4 實作）。
 */
public abstract class BaseException extends RuntimeException {

    private final String errorCode;

    protected BaseException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
