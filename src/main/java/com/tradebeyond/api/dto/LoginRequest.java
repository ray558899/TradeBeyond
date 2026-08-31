package com.tradebeyond.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String account,
        @NotBlank String password
) {
}
