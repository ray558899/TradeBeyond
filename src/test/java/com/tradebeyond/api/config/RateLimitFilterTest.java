package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.controller.ProductController;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.service.ProductService;
import com.tradebeyond.api.service.TokenService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 驗證 RateLimitFilter 在真實 Security filter chain 裡生效（CLAUDE.md Part 2.3）：
 * 不加 addFilters = false，比照 SecurityAuthenticationTest 的既有模式，讓 filter 真的跑。
 * 用 @TestPropertySource 把 app.rate-limit.requests-per-hour 覆蓋成 3，不用真的打
 * 5001 次才能觸發 429。
 *
 * 每個測試方法用不同的 userId 當 key，避免同一個 @WebMvcTest context（Spring 預設
 * 會在同一個測試類別的多個方法間重複使用同一個 context、同一個 InMemoryRateLimiter
 * 單例）裡，不同測試方法的請求次數互相汙染彼此的計數。
 */
@WebMvcTest(controllers = ProductController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@TestPropertySource(properties = {
        "JWT_SECRET=test-jwt-secret-for-rate-limit-filter-test-0123456789",
        "app.rate-limit.requests-per-hour=3"
})
class RateLimitFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @MockitoBean
    private ProductService productService;

    @Test
    void exceedingLimit_returns429WithProblemDetailAndRateLimitHeaders_forAuthenticatedUser() throws Exception {
        stubProduct(1L);
        String token = tokenService.generateAccessToken(9001L);

        // 前 3 次（等於上限）都應該正常通過，且每次回應都要帶三個 X-RateLimit-* header
        // （不是只有超限才附上）
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "2"))
                .andExpect(header().exists("X-RateLimit-Reset"));

        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "1"));

        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "0"));

        // 第 4 次（超過上限）：429 + ProblemDetail 格式 + 三個 header 依然存在
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + token))
                .andExpect(status().is(429))
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().string("X-RateLimit-Limit", "3"))
                .andExpect(header().string("X-RateLimit-Remaining", "0"))
                .andExpect(header().exists("X-RateLimit-Reset"));
    }

    @Test
    void differentAuthenticatedUsers_areRateLimitedIndependently() throws Exception {
        stubProduct(1L);
        String userAToken = tokenService.generateAccessToken(9002L);
        String userBToken = tokenService.generateAccessToken(9003L);

        // userA 打滿上限（3 次），第 4 次被擋
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + userAToken))
                .andExpect(status().is(429));

        // userB 是完全獨立的 key，即使 userA 已經被擋了，userB 的第一次呼叫還是應該正常通過
        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + userBToken))
                .andExpect(status().isOk())
                .andExpect(header().string("X-RateLimit-Remaining", "2"));
    }

    private void stubProduct(Long productId) {
        ProductCategory category = new ProductCategory();
        ReflectionTestUtils.setField(category, "categoryId", 10L);
        Product product = new Product();
        ReflectionTestUtils.setField(product, "productId", productId);
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));
        when(productService.getById(productId)).thenReturn(product);
    }
}
