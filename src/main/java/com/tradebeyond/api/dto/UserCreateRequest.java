package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 建立使用者的請求。password 為明碼，由 service 層 BCrypt hash 後才存進 Entity。
 */
public record UserCreateRequest(
        @NotBlank String username,
        @NotBlank String account,
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
