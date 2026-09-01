package com.tradebeyond.api.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.config.InMemoryRateLimiter;
import com.tradebeyond.api.config.JwtAuthenticationEntryPoint;
import com.tradebeyond.api.config.JwtAuthenticationFilter;
import com.tradebeyond.api.config.RateLimitFilter;
import com.tradebeyond.api.config.SecurityConfig;
import com.tradebeyond.api.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 獨立測試 @RestControllerAdvice 的每個例外分支，不依賴真正的業務 Controller ——
 * 用 ExceptionHandlerTestController（測試專用假 Controller）直接丟出各種例外，
 * 驗證 Advice 有沒有把它們正確轉成 RFC 7807 ProblemDetail + errorCode。
 * 這裡只測例外處理格式，不測認證本身，用 addFilters = false 讓 SecurityConfig
 * filter chain 不生效（認證行為由 SecurityAuthenticationTest 獨立驗證）。
 */
@WebMvcTest(controllers = ExceptionHandlerTestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-controller-test-0123456789")
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void resourceNotFoundException_mapsTo404_withProblemDetailAndErrorCode() throws Exception {
        // ResourceNotFoundException 的所有子類別都要被同一個 handler 攔到，回 404
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("TEST_NOT_FOUND"));
    }

    @Test
    void businessException_mapsTo400_withProblemDetailAndErrorCode() throws Exception {
        mockMvc.perform(get("/test/business"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("TEST_BUSINESS"));
    }

    @Test
    void unauthorizedException_mapsTo401_withProblemDetailAndErrorCode() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").value("TEST_UNAUTHORIZED"));
    }

    @Test
    void externalServiceException_mapsTo503_withProblemDetailAndErrorCode() throws Exception {
        mockMvc.perform(get("/test/external"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.errorCode").value("TEST_EXTERNAL"));
    }

    @Test
    void methodArgumentNotValidException_mapsTo400_withProblemDetailFormat() throws Exception {
        // @Valid 驗證失敗（Spring 自己丟的 MethodArgumentNotValidException，不是我們的例外階層）
        // 也要走同一套 ProblemDetail 格式，不能讓 Spring 預設的錯誤格式外洩
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void objectOptimisticLockingFailureException_mapsTo409_withProblemDetailAndErrorCode() throws Exception {
        // Part 8.3：Order 的併發 PATCH 用 @Version 樂觀鎖擋掉過期寫入，
        // Hibernate/Spring 丟出的 ObjectOptimisticLockingFailureException 一樣要走統一格式，不能外洩成 500
        mockMvc.perform(get("/test/optimistic-lock"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    void conflictException_mapsTo409_withProblemDetailAndErrorCode() throws Exception {
        // ConflictException 的所有子類別（例如 Phase 5 的 DuplicateAccountException）都要被同一個 handler 攔到，回 409
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.errorCode").value("TEST_CONFLICT"));
    }

    @Test
    void forbiddenException_mapsTo403_withProblemDetailAndErrorCode() throws Exception {
        // ForbiddenException 的所有子類別（例如 Part 2.4 的 ForbiddenAccessException，IDOR 歸屬檢查）
        // 都要被同一個 handler 攔到，回 403
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.errorCode").value("TEST_FORBIDDEN"));
    }
}
