package com.tradebeyond.api.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebeyond.api.config.InMemoryRateLimiter;
import com.tradebeyond.api.config.JwtAuthenticationEntryPoint;
import com.tradebeyond.api.config.JwtAuthenticationFilter;
import com.tradebeyond.api.config.RateLimitFilter;
import com.tradebeyond.api.config.SecurityConfig;
import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.dto.RefreshTokenRequest;
import com.tradebeyond.api.dto.TokenResponse;
import com.tradebeyond.api.dto.UserCreateRequest;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.DuplicateAccountException;
import com.tradebeyond.api.exception.InvalidCredentialsException;
import com.tradebeyond.api.exception.InvalidRefreshTokenException;
import com.tradebeyond.api.service.AuthService;
import com.tradebeyond.api.service.TokenService;
import com.tradebeyond.api.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * /api/auth/register、/api/auth/login 在正式 SecurityConfig 裡是 permitAll，
 * 所以這裡刻意「不」加 addFilters = false —— 讓真正的 filter chain 生效，
 * 同時證明這兩個路徑不需要帶 token 也能存取。
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-controller-test-0123456789")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    @Test
    void register_returns201WithUserBody_whenAccountIsAvailable() throws Exception {
        // account 沒有重複時，註冊應該回 201 並帶回使用者資料，且絕對不能回傳 password 欄位
        Users user = new Users();
        ReflectionTestUtils.setField(user, "userId", 1L);
        user.setUsername("name");
        user.setAccount("newaccount");
        user.setPassword("hashed-value");
        when(userService.register(new UserCreateRequest("name", "newaccount", "password123")))
                .thenReturn(user);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UserCreateRequest("name", "newaccount", "password123"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(1))
                .andExpect(jsonPath("$.account").value("newaccount"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void register_returns409WithErrorCode_whenAccountAlreadyExists() throws Exception {
        // account 重複：判斷用 409（Conflict）而不是 400 —— 400 通常代表請求本身格式錯誤，
        // 這裡請求格式完全合法，只是跟伺服器現有的資源狀態衝突，409 的語意更精確
        when(userService.register(new UserCreateRequest("name", "duplicate", "password123")))
                .thenThrow(new DuplicateAccountException("duplicate"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UserCreateRequest("name", "duplicate", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_ACCOUNT"));
    }

    @Test
    void login_returns200WithTokenResponse_whenCredentialsAreCorrect() throws Exception {
        // 帳密正確時，登入應該回 200 並帶回完整的 accessToken/refreshToken/tokenType/expiresIn
        when(authService.login(new LoginRequest("myaccount", "correct-password")))
                .thenReturn(new TokenResponse("access-token-value", "refresh-token-value", "Bearer", 900L));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("myaccount", "correct-password"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token-value"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-token-value"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void login_returns401WithErrorCode_whenCredentialsAreInvalid() throws Exception {
        // 密碼錯誤時，Controller/GlobalExceptionHandler 要把 Service 丟出的
        // InvalidCredentialsException 正確轉成 401 + errorCode
        when(authService.login(new LoginRequest("myaccount", "wrong-password")))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("myaccount", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_CREDENTIALS"));
    }

    @Test
    void refresh_returns200WithNewTokenResponse_whenRefreshTokenIsValid() throws Exception {
        // 這裡不帶 Authorization header —— /api/auth/refresh 就是給「access token 已過期、
        // 沒有合法 Bearer token 可用」的呼叫方使用，必須是 permitAll，靠 body 裡的 refresh token 本身把關
        when(authService.refresh(new RefreshTokenRequest("old-raw-refresh-token")))
                .thenReturn(new TokenResponse("new-access-token", "new-refresh-token", "Bearer", 900L));

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("old-raw-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("new-refresh-token"));
    }

    @Test
    void refresh_returns401WithErrorCode_whenRefreshTokenIsInvalid() throws Exception {
        // refresh token 無效（不存在/已撤銷/已過期）時，Controller 要把 Service 丟出的
        // InvalidRefreshTokenException 正確轉成 401 + errorCode
        when(authService.refresh(new RefreshTokenRequest("bad-refresh-token")))
                .thenThrow(new InvalidRefreshTokenException());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("bad-refresh-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_returns204_regardlessOfWhetherRefreshTokenIsStillValid() throws Exception {
        // 這裡也不帶 Authorization header —— 理由跟 refresh 一樣：/api/auth/logout 必須是 permitAll
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshTokenRequest("some-refresh-token"))))
                .andExpect(status().isNoContent());
    }
}
