package com.tradebeyond.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 正式版：JWT 驗證（Part 3）。/api/auth/register、/api/auth/login、Swagger UI 允許匿名存取，
 * 其餘所有 endpoint 都必須帶合法的 Authorization: Bearer <token>。
 * 沒有任何角色/權限檢查——任何登入使用者都能操作任何 Order/User 資源，這是 Part 3
 * 明確的範圍決定，不加 @PreAuthorize、不加角色欄位。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
            JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint, RateLimitFilter rateLimitFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/register", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout")
                        .permitAll()
                        // /error 是 servlet 容器 ERROR dispatch 的內部轉發目的地（ApiErrorController），
                        // 不是給外部呼叫方直接打的業務路徑。如果不放進白名單，一個 permitAll 端點
                        // （例如 /api/auth/login）內部丟出未預期例外、被容器轉發到 /error 時，這次轉發
                        // 請求會被 anyRequest().authenticated() 擋下：匿名呼叫方本來就沒帶 token，
                        // 就會被誤判成 401，蓋掉真正應該回的 500——這裡放行不影響安全性，因為 /error
                        // 回應的內容本來就是由 ApiErrorController 依實際狀態碼決定，不會洩漏比原本
                        // 錯誤更多的資訊。
                        .requestMatchers("/error").permitAll()
                        // Part 10：CD pipeline 的 smoke test 跟 GCP Uptime Check 都會打
                        // /actuator/health，兩者都不會、也不該被要求先帶 JWT，所以必須 permitAll。
                        .requestMatchers("/actuator/health").permitAll()
                        // 明確列舉，不用 "/swagger-ui**"/"/v3/api-docs**" 這種拿掉斜線的寫法：
                        // Spring 6 的 PathPatternParser 要求 "**" 必須是獨立的路徑片段（前面要有 "/"），
                        // 接在字串中間不會有跨層級的效果，實測會讓 /swagger-ui/index.html、
                        // /v3/api-docs/swagger-config 這類多層路徑整組退化成 401（見 SecurityAuthenticationTest
                        // 的迴歸測試）。逐一列舉雖然要手動加新路徑，但至少行為是經過驗證、可預期的。
                        .requestMatchers(
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**",
                                "/webjars/swagger-ui/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable())
                .exceptionHandling(eh -> eh.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 放在 JwtAuthenticationFilter 之後：這個時間點 SecurityContext 已經被
                // JwtAuthenticationFilter 處理過，RateLimitFilter 才能正確判斷「這個請求有沒有
                // 已驗證的使用者」，決定用 userId 還是 client IP 當限流 key（Part 2.3）。
                .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Part 2.2 要求 BCrypt strength >= 12
        return new BCryptPasswordEncoder(12);
    }
}
