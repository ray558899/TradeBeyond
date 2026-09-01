package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.tradebeyond.api.service.TokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 專門測試 CLAUDE.md Part 3 記錄的已知問題：帶合法 token 打一個完全不存在的路徑，
 * 回 401（誤導成未登入）而不是 404（資源不存在）。
 *
 * 根因是 servlet 容器層級的 ERROR dispatch（DispatcherServlet 找不到 handler，
 * 內部轉發到 /error）會讓 Security filter chain 的 AuthorizationFilter 重新執行一次，
 * 但 JwtAuthenticationFilter 預設跳過 ERROR dispatch，導致這第二次執行時 SecurityContext
 * 是空的，被誤判成未認證。
 *
 * 這個 bug 只有在真正走過 servlet 容器的錯誤頁面轉發機制時才會重現——已經實測驗證過，
 * 同樣的斷言放進 SecurityAuthenticationTest 那種 @WebMvcTest + MockMvc 的 slice 裡，
 * 不管有沒有修都是綠燈（MockMvc 的模擬環境沒有真的觸發容器層級的 ERROR dispatch），
 * 所以這裡改用 webEnvironment = RANDOM_PORT 啟動一個真正的內嵌 Tomcat，
 * 透過真實 HTTP 呼叫重現，這也是唯一能讓這個測試在修正前真的是紅燈的寫法。
 *
 * 同樣的理由，這裡也用來驗證「一個完全不存在的路徑最終真的會被容器轉發到
 * ApiErrorController」這件事本身（CLAUDE.md Part 9.3 記錄的限制：ApiErrorControllerTest
 * 那種 @WebMvcTest 只驗證了格式轉換邏輯，不驗證真的會被轉發過去）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JWT_SECRET=test-jwt-secret-for-error-dispatch-test-0123456789")
@Testcontainers
class SecurityErrorDispatchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TokenService tokenService;

    @Test
    void nonexistentPath_returns404NotBlockedAs401_whenValidTokenProvided() {
        // 帶合法 token 打一個真的不存在的路徑，應該回 404（資源不存在），
        // 不是被容器 ERROR dispatch 的驗證空窗誤判成 401（Part 3 記錄的已知問題，這裡驗證已修正）
        String token = tokenService.generateAccessToken(1L);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/this-path-does-not-exist", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonexistentPath_returns404AsProblemDetail_whenValidTokenProvided() {
        // Part 4.3：這個 404 是容器層級「找不到 handler」轉發到 /error 才產生的，不是
        // GlobalExceptionHandler 攔到的例外，但 client 不該分辨得出來——一樣要是
        // application/problem+json，body 要有 errorCode，不能是 Spring Boot 內建
        // 白板錯誤頁的 {"timestamp":...,"error":"Not Found",...} 格式。
        String token = tokenService.generateAccessToken(1L);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/this-path-does-not-exist", HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"errorCode\":\"NOT_FOUND\"");
    }

    @Test
    void nonexistentPath_stillReturns401_whenNoTokenProvided() {
        // 匿名使用者打一個不存在的路徑，本來就該回 401（不該讓匿名呼叫方知道這個路徑存不存在）。
        // 這條測試是防止「修好帶 token 的 404」這件事時，意外把「匿名 + 不存在路徑」也變成 404，
        // 那樣反而洩漏了路徑資訊給未認證的呼叫方——這條在修正前後都應該維持綠燈，是回歸防護網。
        ResponseEntity<String> response = restTemplate.getForEntity("/api/this-path-does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
