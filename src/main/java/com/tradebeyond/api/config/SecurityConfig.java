package com.tradebeyond.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 這是暫時性設定：Phase 4 只做 Controller 與統一例外處理，JWT 驗證還沒實作，
 * 這裡先放行所有請求（含 /swagger-ui.html），避免 Spring Security 預設的表單登入/401
 * 把這個 Phase 的 MockMvc 測試擋下來。Phase 5 做 JWT（Part 3）時，會把這裡換成
 * 要求登入的版本，並把 /swagger-ui.html 等公開路徑改成明確的白名單。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(formLogin -> formLogin.disable());
        return http.build();
    }
}
