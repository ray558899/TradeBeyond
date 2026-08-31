package com.tradebeyond.api.exception;

/**
 * 外部服務錯誤（如稅率服務逾時），對應 HTTP 502/503。
 */
public abstract class ExternalServiceException extends BaseException {
    protected ExternalServiceException(String errorCode, String message) {
        super(errorCode, message);
    }
}
