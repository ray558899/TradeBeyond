package com.tradebeyond.api.controller;

import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.dto.RefreshTokenRequest;
import com.tradebeyond.api.dto.TokenResponse;
import com.tradebeyond.api.dto.UserCreateRequest;
import com.tradebeyond.api.dto.UserResponse;
import com.tradebeyond.api.service.AuthService;
import com.tradebeyond.api.service.UserService;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    public AuthController(UserService userService, AuthService authService) {
        this.userService = userService;
        this.authService = authService;
    }

    @PostMapping("/api/auth/register")
    @SecurityRequirements()
    public ResponseEntity<UserResponse> register(@Valid @RequestBody UserCreateRequest request) {
        UserResponse response = UserResponse.from(userService.register(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/auth/login")
    @SecurityRequirements()
    public TokenResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/api/auth/refresh")
    @SecurityRequirements()
    public TokenResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refresh(request);
    }

    @PostMapping("/api/auth/logout")
    @SecurityRequirements()
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.noContent().build();
    }
}
