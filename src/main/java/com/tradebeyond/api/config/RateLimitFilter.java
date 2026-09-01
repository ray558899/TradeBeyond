package com.tradebeyond.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebeyond.api.exception.ProblemDetailFactory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 限流 filter（CLAUDE.md Part 2.3）：每個請求打一次 {@link RateLimiter#tryConsume(String)}，
 * 未超過上限也要附上 X-RateLimit-Limit / X-RateLimit-Remaining / X-RateLimit-Reset 三個
 * header（不是只有超限時才附），超過上限回 429 + 統一的 ProblemDetail 格式。
 *
 * 用 addFilterAfter(rateLimitFilter, JwtAuthenticationFilter.class) 掛進
 * SecurityFilterChain（見 SecurityConfig），確保執行到這裡時 SecurityContext 已經被
 * JwtAuthenticationFilter 處理過，才能正確判斷「這個請求有沒有已驗證的使用者」——
 * 這個時間點還在 AnonymousAuthenticationFilter 之前，所以「匿名」在這裡就是
 * SecurityContext 裡完全沒有 Authentication，不用特別處理 AnonymousAuthenticationToken。
 *
 * Key 解析：已驗證使用者用 userId 當 key；否則用 client IP。這個專案的自訂網域前面是
 * Cloudflare + Cloud Run 兩層代理（不是一層），解析真實訪客 IP 依下列優先順序：
 *   1. Cloudflare 專用的 CF-Connecting-IP header——Cloudflare 自己驗證過的真實訪客 IP，
 *      是最可信的來源，且不會被一般呼叫方偽造（只有 Cloudflare 自己的邊緣節點會設這個值，
 *      前提是流量真的有經過 Cloudflare——見下方「已知限制」）。
 *   2. 沒有這個 header 時（例如有人直接打 Cloud Run 預設的 *.run.app 網址，繞過
 *      Cloudflare），fallback 到 X-Forwarded-For 的「最後一段」。這種情況下只剩 Cloud Run
 *      自己這一層代理，最後一段是 Cloud Run 實際觀察到的來源，可信；絕對不能取第一段
 *      ——第一段是呼叫方自己放進 header 裡的值，可以任意偽造，之前的實作就是取錯了這裡。
 *   3. 都沒有的話 fallback 到 request.getRemoteAddr()（本機 docker-compose 開發，
 *      前面沒有任何代理）。
 *
 * 已知限制（CLAUDE.md Part 2.3 記錄，這次不處理）：Cloud Run 預設的 *.run.app 網址無論
 * 有沒有設定自訂網域都還是可以直接打，除非額外鎖起來。攻擊者直接打那個網址就完全繞過
 * Cloudflare，可以自己偽造任意的 CF-Connecting-IP header（Cloudflare 之外沒有任何機制
 * 會擋掉或驗證這個 header），優先順序 1 的信任基礎就不成立了，等於整組限流的 IP
 * 判斷都可以被繞過。真正堵死這個洞需要在部署層面限制 ingress（例如 Cloudflare
 * Authenticated Origin Pulls / mTLS，或應用層驗證一個只有 Cloudflare 知道的共用密鑰
 * header），不是限流功能本身該做的事，等 Part 10 真的部署時再處理。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LIMIT_HEADER = "X-RateLimit-Limit";
    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String RESET_HEADER = "X-RateLimit-Reset";

    // Swagger/OpenAPI 文件路徑跟 /error 不套用限流：這些不是業務或驗證相關的 endpoint，
    // 限流保護的目標是「防止 login/register 被暴力破解、業務 endpoint 被灌爆」，
    // 對文件頁面套用限流沒有實質防護效果，反而可能讓開發者在瀏覽器裡多刷新幾次
    // Swagger UI（頁面載入時會自動打好幾個背景請求）就把自己匿名 IP 的配額用掉，
    // 干擾到後續真正要測試的業務請求。/error 則是 servlet 容器內部轉發的目的地，
    // 不是外部呼叫方直接打的路徑，該計費的是原始請求，不是這次內部轉發。
    // /actuator/health（Part 10）也要永遠排除：GCP Uptime Check、CD pipeline 的 smoke test
    // 都會定期打這個 endpoint，這些呼叫來自匿名、跟其他匿名流量共用同一個 IP-based 限流
    // bucket——如果不排除，健康檢查有可能被同一個來源的其他匿名請求（或健康檢查自己頻繁
    // 呼叫累積）誤擋成 429，導致 Cloud Run 或 CD 誤判服務不健康。
    private static final Set<String> EXACT_EXCLUDED_PATHS =
            Set.of("/error", "/swagger-ui.html", "/v3/api-docs", "/v3/api-docs.yaml", "/actuator/health");
    private static final List<String> PREFIX_EXCLUDED_PATHS =
            List.of("/swagger-ui/", "/v3/api-docs/", "/webjars/swagger-ui/");

    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (EXACT_EXCLUDED_PATHS.contains(path)) {
            return true;
        }
        return PREFIX_EXCLUDED_PATHS.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = resolveKey(request);
        RateLimitResult result = rateLimiter.tryConsume(key);

        response.setHeader(LIMIT_HEADER, String.valueOf(result.limit()));
        response.setHeader(REMAINING_HEADER, String.valueOf(result.remainingTokens()));
        response.setHeader(RESET_HEADER, String.valueOf(result.resetEpochSeconds()));

        if (!result.allowed()) {
            ProblemDetail problem = ProblemDetailFactory.create(
                    HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "已超過每小時可呼叫次數上限，請稍後再試");
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), problem);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String resolveKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return "user:" + authentication.getPrincipal();
        }
        return "ip:" + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");
        if (StringUtils.hasText(cfConnectingIp)) {
            return cfConnectingIp.trim();
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            String[] hops = forwardedFor.split(",");
            return hops[hops.length - 1].trim();
        }

        return request.getRemoteAddr();
    }
}
