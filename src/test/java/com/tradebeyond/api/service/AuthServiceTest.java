package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UsersRepository usersRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenService tokenService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(usersRepository, refreshTokenRepository, passwordEncoder, tokenService);
    }

    private Users userWithHashedPassword(String hashedPassword) {
        Users user = new Users();
        ReflectionTestUtils.setField(user, "userId", 1L);
        user.setUsername("name");
        user.setAccount("myaccount");
        user.setPassword(hashedPassword);
        return user;
    }

    @Test
    void login_returnsTokenResponse_whenAccountAndPasswordAreCorrect() {
        Users user = userWithHashedPassword("hashed-value");
        when(usersRepository.findByAccount("myaccount")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed-value")).thenReturn(true);
        when(tokenService.generateAccessToken(1L)).thenReturn("access-token-value");
        when(tokenService.generateRefreshTokenValue()).thenReturn("refresh-token-value");
        when(tokenService.getAccessTokenExpirySeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirySeconds()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenResponse result = authService.login(new LoginRequest("myaccount", "correct-password"));

        assertThat(result.accessToken()).isEqualTo("access-token-value");
        assertThat(result.refreshToken()).isEqualTo("refresh-token-value");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(900L);
    }

    @Test
    void login_persistsHashedRefreshToken_notThePlaintextValue() {
        // Refresh Token 一定要存進 DB（之後登出/撤銷才有依據），但存的必須是雜湊值，不是明碼——
        // 明碼只能回給 client，DB 裡永遠不該出現使用者實際會拿在手上的那個值
        Users user = userWithHashedPassword("hashed-value");
        when(usersRepository.findByAccount("myaccount")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("correct-password", "hashed-value")).thenReturn(true);
        when(tokenService.generateAccessToken(1L)).thenReturn("access-token-value");
        when(tokenService.generateRefreshTokenValue()).thenReturn("refresh-token-value");
        when(tokenService.hashRefreshToken("refresh-token-value")).thenReturn("hashed-refresh-token-value");
        when(tokenService.getRefreshTokenExpirySeconds()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenResponse result = authService.login(new LoginRequest("myaccount", "correct-password"));

        org.mockito.ArgumentCaptor<RefreshToken> captor = org.mockito.ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(user);
        assertThat(captor.getValue().getToken()).isEqualTo("hashed-refresh-token-value");
        // 回給 client 的仍然是明碼，client 需要這個值才能之後用來換發新 token
        assertThat(result.refreshToken()).isEqualTo("refresh-token-value");
    }

    @Test
    void login_throwsInvalidCredentialsException_whenAccountDoesNotExist() {
        when(usersRepository.findByAccount("no-such-account")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("no-such-account", "any-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_throwsInvalidCredentialsException_whenPasswordIsWrong() {
        Users user = userWithHashedPassword("hashed-value");
        when(usersRepository.findByAccount("myaccount")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-value")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest("myaccount", "wrong-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void login_accountNotFoundAndWrongPassword_produceIdenticalErrorContent() {
        // 防帳號枚舉：不能讓 client 從錯誤內容分辨「帳號不存在」跟「密碼錯誤」是兩種不同情況
        when(usersRepository.findByAccount("no-such-account")).thenReturn(Optional.empty());
        Users user = userWithHashedPassword("hashed-value");
        when(usersRepository.findByAccount("myaccount")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong-password", "hashed-value")).thenReturn(false);

        InvalidCredentialsException whenAccountMissing = catchInvalidCredentials(
                () -> authService.login(new LoginRequest("no-such-account", "any-password")));
        InvalidCredentialsException whenPasswordWrong = catchInvalidCredentials(
                () -> authService.login(new LoginRequest("myaccount", "wrong-password")));

        assertThat(whenAccountMissing.getErrorCode()).isEqualTo(whenPasswordWrong.getErrorCode());
        assertThat(whenAccountMissing.getMessage()).isEqualTo(whenPasswordWrong.getMessage());
    }

    private InvalidCredentialsException catchInvalidCredentials(Runnable runnable) {
        try {
            runnable.run();
        } catch (InvalidCredentialsException ex) {
            return ex;
        }
        throw new AssertionError("Expected InvalidCredentialsException to be thrown");
    }

    private RefreshToken storedRefreshToken(Users user, Instant expiresAt, Instant revokedAt) {
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setToken("stored-hash-value");
        refreshToken.setExpiresAt(expiresAt);
        refreshToken.setRevokedAt(revokedAt);
        return refreshToken;
    }

    @Test
    void refresh_throwsInvalidRefreshTokenException_whenTokenHashIsNotFound() {
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw-refresh-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_throwsInvalidRefreshTokenException_whenTokenIsRevoked() {
        Users user = userWithHashedPassword("hashed-value");
        RefreshToken revoked = storedRefreshToken(user, Instant.now().plusSeconds(3600), Instant.now().minusSeconds(60));
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw-refresh-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_throwsInvalidRefreshTokenException_whenTokenIsExpired() {
        Users user = userWithHashedPassword("hashed-value");
        RefreshToken expired = storedRefreshToken(user, Instant.now().minusSeconds(60), null);
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(new RefreshTokenRequest("raw-refresh-token")))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void refresh_rotatesToken_revokingOldAndIssuingNewTokenResponse_whenTokenIsValid() {
        // Refresh Token Rotation：驗證通過後，舊的 token 立刻標記撤銷，
        // 同時簽發一組全新的 access/refresh token，不能讓舊 token 繼續有效
        Users user = userWithHashedPassword("hashed-value");
        RefreshToken valid = storedRefreshToken(user, Instant.now().plusSeconds(3600), null);
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.of(valid));
        when(tokenService.generateAccessToken(1L)).thenReturn("new-access-token");
        when(tokenService.generateRefreshTokenValue()).thenReturn("new-raw-refresh-token");
        when(tokenService.hashRefreshToken("new-raw-refresh-token")).thenReturn("new-hash-value");
        when(tokenService.getAccessTokenExpirySeconds()).thenReturn(900L);
        when(tokenService.getRefreshTokenExpirySeconds()).thenReturn(2592000L);
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TokenResponse result = authService.refresh(new RefreshTokenRequest("raw-refresh-token"));

        assertThat(result.accessToken()).isEqualTo("new-access-token");
        assertThat(result.refreshToken()).isEqualTo("new-raw-refresh-token");
        assertThat(result.tokenType()).isEqualTo("Bearer");
        assertThat(result.expiresIn()).isEqualTo(900L);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository, times(2)).save(captor.capture());
        RefreshToken revokedOldToken = captor.getAllValues().get(0);
        RefreshToken savedNewToken = captor.getAllValues().get(1);
        assertThat(revokedOldToken).isSameAs(valid);
        assertThat(revokedOldToken.getRevokedAt()).isNotNull();
        assertThat(savedNewToken.getToken()).isEqualTo("new-hash-value");
        assertThat(savedNewToken.getUser()).isSameAs(user);
    }

    @Test
    void logout_revokesToken_whenTokenExistsAndNotYetRevoked() {
        Users user = userWithHashedPassword("hashed-value");
        RefreshToken active = storedRefreshToken(user, Instant.now().plusSeconds(3600), null);
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.of(active));

        authService.logout(new RefreshTokenRequest("raw-refresh-token"));

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(active);
        assertThat(captor.getValue().getRevokedAt()).isNotNull();
    }

    @Test
    void logout_doesNothingAndDoesNotThrow_whenTokenDoesNotExist() {
        // 登出一個不存在的 token 視為冪等成功——不能讓 client 從「有沒有丟例外」反推出
        // 這個 token 到底存不存在過（呼應防帳號枚舉的同一個精神），所以這裡完全不丟例外
        when(tokenService.hashRefreshToken("unknown-token")).thenReturn("unknown-hash");
        when(refreshTokenRepository.findByTokenHash("unknown-hash")).thenReturn(Optional.empty());

        authService.logout(new RefreshTokenRequest("unknown-token"));

        verify(refreshTokenRepository, never()).save(any());
    }

    @Test
    void logout_isIdempotent_whenTokenIsAlreadyRevoked() {
        // 登出一個已經登出過的 token 也視為冪等成功，不是錯誤
        Users user = userWithHashedPassword("hashed-value");
        Instant firstRevokedAt = Instant.now().minusSeconds(120);
        RefreshToken alreadyRevoked = storedRefreshToken(user, Instant.now().plusSeconds(3600), firstRevokedAt);
        when(tokenService.hashRefreshToken("raw-refresh-token")).thenReturn("stored-hash-value");
        when(refreshTokenRepository.findByTokenHash("stored-hash-value")).thenReturn(Optional.of(alreadyRevoked));

        authService.logout(new RefreshTokenRequest("raw-refresh-token"));

        assertThat(alreadyRevoked.getRevokedAt()).isNotNull();
    }
}
