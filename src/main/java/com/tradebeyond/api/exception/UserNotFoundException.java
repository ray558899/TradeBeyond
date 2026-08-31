package com.tradebeyond.api.exception;

public class UserNotFoundException extends ResourceNotFoundException {
    public UserNotFoundException(Long userId) {
        super("USER_NOT_FOUND", "找不到 userId=" + userId + " 的使用者");
    }
}
