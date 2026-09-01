package com.tradebeyond.api.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * CLAUDE.md Part 2.3 的 gotcha：自訂網域前面是 Cloudflare + Cloud Run 兩層代理，
 * 不是一層。RateLimitFilter 解析匿名來源 IP 必須：
 *   1. 優先信任 Cloudflare 專用的 CF-Connecting-IP（Cloudflare 驗證過的真實訪客 IP）。
 *   2. 沒有這個 header 時 fallback 到 X-Forwarded-For 的「最後一段」（只剩 Cloud Run
 *      這一層代理時，最後一段是可信的；「第一段」不可信，因為那是呼叫方自己可以隨意
 *      塞進 header 裡的值）。
 *   3. 都沒有時 fallback 到 request.getRemoteAddr()（本機 docker-compose 開發，前面沒有代理）。
 *
 * 這裡不加 addFilters = false，讓真實的 Security filter chain（含 RateLimitFilter）生效，
 * 比照 AuthEndpointRateLimitTest 的既有模式。
 */
@WebMvcTest(controllers = AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@TestPropertySource(properties = {
        "JWT_SECRET=test-jwt-secret-for-rate-limit-client-ip-test-0123456789",
        "app.rate-limit.requests-per-hour=3"
})
class RateLimitClientIpResolutionTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserService userService;

    @Test
    void sameCfConnectingIp_isTreatedAsSameKey_regardlessOfDifferentXForwardedFor() throws Exception {
        // 驗證限流 key 解析優先信任 CF-Connecting-IP，不會被呼叫方能自己塞的 X-Forwarded-For 干擾
        stubLoginSuccess();

        // CF-Connecting-IP 固定不變，即使每次搭配不同的 X-Forwarded-For（呼叫方能自己
        // 塞這個 header，內容不可信），還是要被視為「同一個」限流 key
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginRequest()
                            .header("CF-Connecting-IP", "1.2.3.4")
                            .header("X-Forwarded-For", "attacker-fake-value-" + i))
                    .andExpect(status().isOk());
        }

        // 第 4 次：同一個 CF-Connecting-IP，即使 X-Forwarded-For 又換了一個新的假值，還是要被擋
        mockMvc.perform(loginRequest()
                        .header("CF-Connecting-IP", "1.2.3.4")
                        .header("X-Forwarded-For", "attacker-fake-value-final"))
                .andExpect(status().is(429));
    }

    @Test
    void differentCfConnectingIp_isRateLimitedIndependently() throws Exception {
        // 驗證不同的 CF-Connecting-IP 各自獨立計算限流額度，不會互相干擾
        stubLoginSuccess();

        // 一個 CF-Connecting-IP 打滿上限
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginRequest().header("CF-Connecting-IP", "5.6.7.8"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(loginRequest().header("CF-Connecting-IP", "5.6.7.8"))
                .andExpect(status().is(429));

        // 換一個不同的 CF-Connecting-IP：即使前一個已經被擋了，這個的第一次呼叫還是應該正常通過，
        // 證明是各自獨立計算，不會互相干擾
        mockMvc.perform(loginRequest().header("CF-Connecting-IP", "9.9.9.9"))
                .andExpect(status().isOk());
    }

    @Test
    void noCfConnectingIp_fallsBackToLastSegmentOfXForwardedFor_ignoringFakePrefixes() throws Exception {
        // 沒有 CF-Connecting-IP 時，驗證 fallback 到 X-Forwarded-For 的最後一段，
        // 且不受呼叫方能自己塞的前面假段影響
        stubLoginSuccess();

        // 沒有 CF-Connecting-IP（直連 Cloud Run 的 *.run.app 網址，繞過 Cloudflare，只剩一層代理）：
        // 應該用 X-Forwarded-For 的最後一段當 key，前面的假值（呼叫方自己可以塞的）不該影響判斷。
        // 最後一段（10.0.0.99，代表 Cloud Run 這層代理實際觀察到的來源）在這三次請求中保持不變。
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(loginRequest()
                            .header("X-Forwarded-For", "fake-hop-" + i + ", another-fake-hop, 10.0.0.99"))
                    .andExpect(status().isOk());
        }

        // 第 4 次：最後一段依然是 10.0.0.99，前面的假值又換了新的，還是要被擋
        mockMvc.perform(loginRequest()
                        .header("X-Forwarded-For", "yet-another-fake-hop, 10.0.0.99"))
                .andExpect(status().is(429));
    }

    private void stubLoginSuccess() {
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new TokenResponse("access-token", "refresh-token", "Bearer", 900));
    }

    private MockHttpServletRequestBuilder loginRequest() {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"demo\",\"password\":\"password123\"}");
    }
}
