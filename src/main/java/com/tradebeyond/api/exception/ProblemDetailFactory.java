package com.tradebeyond.api.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * 統一產生 RFC 7807 ProblemDetail + errorCode 的共用工具。
 * GlobalExceptionHandler（Spring MVC 內的例外攔截）跟 SecurityConfig 的
 * AuthenticationEntryPoint（Spring Security filter chain 攔截，發生在
 * DispatcherServlet 之前，@RestControllerAdvice 管不到）都呼叫這裡，
 * 確保兩邊產出完全一致的錯誤格式，不用維護兩份重複邏輯。
 */
public final class ProblemDetailFactory {

    private ProblemDetailFactory() {
    }

    public static ProblemDetail create(HttpStatus status, String errorCode, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        return problem;
    }
}
