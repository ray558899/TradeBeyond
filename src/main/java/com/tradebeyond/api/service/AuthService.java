package com.tradebeyond.api.service;

import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.dto.RefreshTokenRequest;
import com.tradebeyond.api.dto.TokenResponse;
import com.tradebeyond.api.entity.RefreshToken;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.InvalidCredentialsException;
import com.tradebeyond.api.exception.InvalidRefreshTokenException;
import com.tradebeyond.api.repository.RefreshTokenRepository;
import com.tradebeyond.api.repository.UsersRepository;
import java.time.Instant;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UsersRepository usersRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;

    public AuthService(UsersRepository usersRepository, RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder, TokenService tokenService) {
        this.usersRepository = usersRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        // usersRepository.findByAccount 底層受 Users 的 @SQLRestriction("delete_at IS NULL") 保護，
        // 已軟刪除的使用者天然查不到，跟「帳號不存在」走同一個分支、丟同一種例外 —— 不需要額外判斷 delete_at，
        // 也因此帳號不存在／密碼錯誤／帳號已被軟刪除，三種情況的錯誤格式自然完全一致，防帳號枚舉。
        Users user = usersRepository.findByAccount(request.account())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException();
        }

        return issueTokens(user);
    }

    @Transactional
    public TokenResponse refresh(RefreshTokenRequest request) {
        String tokenHash = tokenService.hashRefreshToken(request.refreshToken());
        RefreshToken existing = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidRefreshTokenException::new);

        if (existing.getRevokedAt() != null || existing.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException();
        }

        // Refresh Token Rotation：驗證通過就立刻撤銷這個舊 token，換一組全新的，
        // 舊 token 之後不管有沒有被偷，都已經失效了，不能再拿來用
        existing.setRevokedAt(Instant.now());
        refreshTokenRepository.save(existing);

        return issueTokens(existing.getUser());
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        // 找不到、或已經撤銷過，都視為冪等成功（不丟例外）：登出一個已經登出的東西不算錯誤，
        // 也呼應防帳號枚舉的同一個精神——不能讓 client 從「有沒有丟例外」反推出這個 token 存不存在過。
        String tokenHash = tokenService.hashRefreshToken(request.refreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(existing -> {
            existing.setRevokedAt(Instant.now());
            refreshTokenRepository.save(existing);
        });
    }

    /**
     * login 與 refresh 共用的「簽發一組全新 access/refresh token」邏輯：
     * 產生 access token、產生 refresh token 明碼、雜湊後存 DB、回傳含明碼的 TokenResponse。
     */
    private TokenResponse issueTokens(Users user) {
        String accessToken = tokenService.generateAccessToken(user.getUserId());
        String refreshTokenValue = tokenService.generateRefreshTokenValue();

        // DB 只存雜湊值，明碼只回給 client 一次——之後任何要用 refresh token 查表的地方，
        // 一律是「拿使用者傳來的原始 token 算雜湊，再拿雜湊去查」，DB 裡永遠不會出現明碼本身
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken(tokenService.hashRefreshToken(refreshTokenValue));
        refreshToken.setExpiresAt(Instant.now().plusSeconds(tokenService.getRefreshTokenExpirySeconds()));
        refreshTokenRepository.save(refreshToken);

        return new TokenResponse(accessToken, refreshTokenValue, "Bearer", tokenService.getAccessTokenExpirySeconds());
    }
}
