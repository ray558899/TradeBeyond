package com.tradebeyond.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.controller.AuthController;
import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.dto.TokenResponse;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * CLAUDE.md Part 2.3 明確要求：/api/auth/login、/api/auth/register 這兩個目前
 * 完全暴露的 endpoint，即使是 permitAll、匿名可打，也要被限流機制保護（用 client IP
 * 當 key），不能因為不需要登入就跳過檢查。這裡不加 addFilters = false，讓真實的
 * Security filter chain（含 RateLimitFilter）生效，比照 RateLimitFilterTest 的模式，
 * 用 @TestPropertySource 把上限覆蓋成 3。
 *
 * AuthService 整個 mock 掉：這裡只在乎「匿名 + IP 限流」本身，不需要真的建立帳號/
 * 走過密碼驗證（那些已經在 AuthServiceTest／AuthServiceIntegrationTest 覆蓋過）。
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@TestPropertySource(properties = {
        "JWT_SECRET=test-jwt-secret-for-auth-endpoint-rate-limit-test-0123456789",
        "app.rate-limit.requests-per-hour=3"
})
class AuthEndpointRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @Test
    void anonymousRequestsToLogin_areRateLimitedByClientIp_andReturn429AfterLimitExceeded() throws Exception {
        // 匿名連續打 /api/auth/login 超過上限，即使是 permitAll 端點也要被限流擋下來，
        // 驗證 login/register 這種未登入前的 endpoint 沒有因為不用帶 token 就跳過限流檢查
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new TokenResponse("access-token", "refresh-token", "Bearer", 900));

        // 同一個匿名來源（同一個 client IP）連續打 POST /api/auth/login，
        // 即使這個 endpoint 是 permitAll、完全不用帶 token，前 3 次仍應該正常通過
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginRequestFrom("203.0.113.10"))
                    .andExpect(status().isOk());
        }

        // 第 4 次（同一個 IP，超過上限）：429 + ProblemDetail，不是繼續放行也不是誤判成別種錯誤
        mockMvc.perform(loginRequestFrom("203.0.113.10"))
                .andExpect(status().is(429))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void anonymousRequestsFromDifferentClientIps_areRateLimitedIndependently() throws Exception {
        // 一個匿名 IP 被限流擋下來後，換一個不同的匿名 IP 打同一個 endpoint 應該不受影響，
        // 證明限流是以 client IP 為單位各自獨立計算，不會互相干擾
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new TokenResponse("access-token", "refresh-token", "Bearer", 900));

        // IP A 打滿上限，第 4 次被擋
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginRequestFrom("198.51.100.20"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(loginRequestFrom("198.51.100.20"))
                .andExpect(status().is(429));

        // 換一個不同的 IP：即使 IP A 已經被擋了，IP B 的第一次呼叫還是應該正常通過，
        // 證明限流是「以 IP 為單位」各自獨立計算，不會互相干擾
        mockMvc.perform(loginRequestFrom("198.51.100.99"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder loginRequestFrom(String remoteIp) {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"demo\",\"password\":\"password123\"}")
                .with(request -> {
                    request.setRemoteAddr(remoteIp);
                    return request;
                });
    }
}
