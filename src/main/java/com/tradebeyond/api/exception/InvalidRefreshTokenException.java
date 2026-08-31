package com.tradebeyond.api.exception;

/**
 * refresh token 查不到、已撤銷、或已過期，一律丟這個例外——跟 INVALID_CREDENTIALS
 * （登入帳密錯誤）分開，讓 client 能依 errorCode 判斷該導去重新登入畫面，
 * 還是單純顯示「帳密錯誤」。
 */
public class InvalidRefreshTokenException extends UnauthorizedException {
    public InvalidRefreshTokenException() {
        super("INVALID_REFRESH_TOKEN", "refresh token 無效、已過期或已撤銷，請重新登入");
    }
}
