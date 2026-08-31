package com.tradebeyond.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebeyond.api.exception.ProblemDetailFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Spring Security filter chain 攔下未帶 token／token 無效的請求時呼叫這裡。
 * 這個時間點還沒進到 DispatcherServlet，@RestControllerAdvice 完全管不到，
 * 所以要手動組出跟 GlobalExceptionHandler 一致的 ProblemDetail JSON 寫回 response
 * （共用 ProblemDetailFactory），不能只丟一個空 body 的 401（Part 4.3）。
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ProblemDetail problem = ProblemDetailFactory.create(
                HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "需要登入才能存取此資源，請提供合法的 Authorization: Bearer <token>");
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // 用 OutputStream 而不是 Writer：Writer 預設走 servlet 容器的字元集（ISO-8859-1），
        // 中文訊息會變亂碼；直接寫 UTF-8 bytes 才不會有這個問題。
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
