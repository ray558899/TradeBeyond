package com.tradebeyond.api.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.service.TokenService;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 窄範圍測試 ApiErrorController 本身的格式轉換邏輯：直接呼叫 /error（Spring Boot
 * 預設的 server.error.path），用標準的 jakarta.servlet.error.* request attributes
 * 模擬 servlet 容器 ERROR dispatch 轉發過來時會帶的資訊（狀態碼、原始路徑、例外），
 * 不依賴真的觸發容器層級的 ERROR dispatch——那個要用真正的 servlet 容器驗證，
 * 見 SecurityErrorDispatchIntegrationTest（CLAUDE.md Part 9.3 記錄的限制）。
 * 用 addFilters = false 排除 Security filter chain，這裡只在乎格式轉換邏輯本身；
 * @WebMvcTest 的元件掃描仍然會嘗試建立 JwtAuthenticationFilter 這個 bean（它是
 * jakarta.servlet.Filter，不受 controllers 篩選限制），所以還是要比照
 * GlobalExceptionHandlerTest 的既有模式把它跟依賴一起 @Import 進來，讓 context 能啟動。
 */
@WebMvcTest(controllers = ApiErrorController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-api-error-controller-test-0123456789")
class ApiErrorControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notFoundStatus_mapsToProblemDetail_withNotFoundErrorCode() throws Exception {
        // 模擬容器 ERROR dispatch 帶著 404 狀態碼轉發過來，驗證 ApiErrorController 有把它
        // 轉成統一的 ProblemDetail 格式（errorCode: NOT_FOUND），而不是 Spring Boot 內建的白板錯誤頁
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 404)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/this-path-does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.errorCode").value("NOT_FOUND"))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void internalServerErrorStatus_mapsToProblemDetail_withInternalServerErrorCode() throws Exception {
        // 不用 requestAttr 帶 RequestDispatcher.ERROR_EXCEPTION：那個 key 等同
        // WebUtils.ERROR_EXCEPTION_ATTRIBUTE，MockMvc 的 TestDispatcherServlet 處理完
        // 請求後，若偵測到這個 attribute 還在，會把它當成「未被處理的例外」重新丟出來，
        // 導致測試本身直接因未捕捉例外而失敗——這裡只需要驗證狀態碼→格式的轉換邏輯，
        // 不需要真的模擬例外物件本身。
        mockMvc.perform(get("/error")
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/api/product/1")
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, "模擬未被任何 @ExceptionHandler 攔到的未預期例外"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.detail").exists());
    }
}
