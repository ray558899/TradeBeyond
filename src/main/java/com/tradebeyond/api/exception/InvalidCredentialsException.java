package com.tradebeyond.api.exception;

/**
 * account 不存在、密碼錯誤、或帳號已被軟刪除，一律丟這個例外、回傳完全相同的錯誤格式，
 * 避免 client 從錯誤內容分辨出「帳號是否存在」（帳號枚舉攻擊）。
 */
public class InvalidCredentialsException extends UnauthorizedException {
    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "帳號或密碼錯誤");
    }
}
