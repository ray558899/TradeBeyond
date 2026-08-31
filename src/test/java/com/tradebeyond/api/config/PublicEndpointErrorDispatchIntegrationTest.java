package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 專門測試 CLAUDE.md Part 3 記錄的問題：匿名使用者打一個 permitAll 端點，若觸發
 * 未預期的 500，會被 AuthorizationFilter 攔成 401，蓋掉真正的 500。
 *
 * 根因：/error 這個路徑本身（在這次修正前）不在 SecurityConfig 的 permitAll 白名單裡，
 * anyRequest().authenticated() 會擋住它。原始請求（/api/auth/login）雖然是 permitAll，
 * 但 controller 內部丟出未預期例外（不屬於 BaseException 階層，GlobalExceptionHandler
 * 攔不到）時，servlet 容器會把請求內部轉發到 /error；這次轉發本身要重新走一次
 * Security filter chain，/error 落在 anyRequest().authenticated() 規則下，匿名請求
 * （沒帶 token）在這裡就被判定成未認證，回 401，蓋掉了真正應該回的 500。
 *
 * 這牽涉真實容器層級的 ERROR dispatch，@WebMvcTest/MockMvc 測不出來
 * （CLAUDE.md Part 9.3 記錄的限制：模擬環境沒有真的觸發容器層級的 ERROR dispatch），
 * 比照 SecurityErrorDispatchIntegrationTest 的既有模式，用 webEnvironment = RANDOM_PORT
 * 啟動真正的內嵌 Tomcat，透過真實 HTTP 呼叫重現。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "JWT_SECRET=test-jwt-secret-for-public-endpoint-error-dispatch-test-0123456789")
@Testcontainers
class PublicEndpointErrorDispatchIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    @Autowired
    private TestRestTemplate restTemplate;

    @MockitoBean
    private AuthService authService;

    @Test
    void anonymousRequestToPermitAllEndpoint_returns500NotMaskedAs401_whenUnexpectedExceptionThrown() {
        // 純 RuntimeException，不屬於 BaseException 階層——模擬真的系統錯誤（例如未預期的
        // NullPointerException、資料庫連線問題），不是業務邏輯例外，這樣才會真的落到
        // GlobalExceptionHandler 攔不到、被容器轉發到 /error 的路徑。
        when(authService.login(any(LoginRequest.class))).thenThrow(new RuntimeException("模擬未預期的系統錯誤"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<LoginRequest> request = new HttpEntity<>(new LoginRequest("any-account", "any-password"), headers);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/auth/login", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).contains("\"errorCode\":\"INTERNAL_SERVER_ERROR\"");
    }
}
