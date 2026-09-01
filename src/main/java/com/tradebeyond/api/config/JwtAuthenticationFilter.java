package com.tradebeyond.api.config;

import com.tradebeyond.api.service.TokenService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 解析 Authorization: Bearer <token>，驗證成功就把 userId 設進 SecurityContext。
 * 沒帶 token、或 token 無效/過期，這裡不主動擋，交給 SecurityConfig 的
 * authorizeHttpRequests 規則判斷該路徑需不需要登入 —— 這個 filter 只負責
 * 「有沒有帶合法的 token」，不負責「這個路徑要不要驗證」。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    public JwtAuthenticationFilter(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // 預設值 true 會讓這個 filter 跳過 Spring Boot 找不到 handler 時內部轉發到 /error 的
        // ERROR dispatch；但 Security filter chain 的 AuthorizationFilter 在 ERROR dispatch 時
        // 還是會重新執行一次，STATELESS 模式下 SecurityContext 是每次 filter chain 都重新推導的，
        // 這裡如果不重新解析 token，第二次執行就會看到空的 SecurityContext，被誤判成未認證，
        // 導致「帶合法 token 打一個不存在的路徑」變成 401 而不是 404。改成 false 讓這個 filter
        // 在 ERROR dispatch 也重新執行、重新解析 header，把認證狀態帶過去。
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length());
            try {
                Long userId = tokenService.parseAccessToken(token);
                UsernamePasswordAuthenticationToken authentication =
                        new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ex) {
                // token 無效/過期：不設定 SecurityContext，讓後續 authorizeHttpRequests 規則
                // 判斷這個路徑需不需要登入（需要的話會自然回 401，不需要的話照樣放行）
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
