package com.tradebeyond.api.dto;

import com.tradebeyond.api.entity.Users;
import java.time.Instant;

/**
 * 使用者回應。不含 password，絕不外洩密碼相關資訊。
 */
public record UserResponse(
        Long userId,
        String username,
        String account,
        Instant createAt,
        Instant updateAt
) {
    public static UserResponse from(Users user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getAccount(),
                user.getCreateAt(),
                user.getUpdateAt()
        );
    }
}
