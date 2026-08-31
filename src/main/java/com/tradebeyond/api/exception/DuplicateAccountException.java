package com.tradebeyond.api.exception;

public class DuplicateAccountException extends ConflictException {
    public DuplicateAccountException(String account) {
        super("DUPLICATE_ACCOUNT", "account=" + account + " 已被註冊");
    }
}
