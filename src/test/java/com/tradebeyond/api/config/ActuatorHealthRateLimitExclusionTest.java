package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * CLAUDE.md Part 2.3：/actuator/health 要永遠不受限流影響（GCP Uptime Check、CD smoke
 * test 都要能穩定打到，不該因為共用某個 IP 的限流額度而被誤擋）。
 *
 * 這裡用 @SpringBootTest(webEnvironment = RANDOM_PORT) + Testcontainers + 真實 HTTP 呼叫
 * （而不是 @WebMvcTest slice）：/actuator/health 的真正 handler 是
 * spring-boot-starter-actuator 的自動組態掛上去的，@WebMvcTest(controllers = ...) 這種
 * 窄範圍 slice 不會載入它，就算通過了 RateLimitFilter 也只會是 404，驗證不到「真的回 200」
 * 這件事——要驗證這個排除規則的完整效果（含真正的 handler 回應），需要一個真實啟動的 app。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "JWT_SECRET=test-jwt-secret-for-actuator-health-rate-limit-test-0123456789",
                "app.rate-limit.requests-per-hour=2"
        })
@Testcontainers
class ActuatorHealthRateLimitExclusionTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void actuatorHealth_neverRateLimited_evenAfterExceedingConfiguredLimit() {
        // 上限設成 2，連續打 4 次（超過上限兩倍），每一次都應該正常回 200，
        // 不會像其他匿名 endpoint 那樣在第 3 次開始被擋成 429
        for (int i = 0; i < 4; i++) {
            ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).contains("\"status\":\"UP\"");
            // 排除規則等同 Swagger 路徑目前的行為：完全不進限流邏輯，所以不會附上這三個 header
            assertThat(response.getHeaders().get("X-RateLimit-Limit")).isNull();
            assertThat(response.getHeaders().get("X-RateLimit-Remaining")).isNull();
            assertThat(response.getHeaders().get("X-RateLimit-Reset")).isNull();
        }
    }
}
