package com.tradebeyond.api.exception;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 統一例外處理：把自訂例外階層（Part 4.3）與 Spring 自己丟出的驗證例外，
 * 都轉成同一種 RFC 7807 ProblemDetail 格式，避免任一種錯誤格式外洩到 client。
 * 新增一種錯誤類型只需要新增一個 exception class（繼承現有 4 個分類之一），
 * 這裡的攔截邏輯不用跟著改。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
        return buildProblemDetail(HttpStatus.NOT_FOUND, ex);
    }

    @ExceptionHandler(BusinessException.class)
    public ProblemDetail handleBusiness(BusinessException ex) {
        return buildProblemDetail(HttpStatus.BAD_REQUEST, ex);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex) {
        return buildProblemDetail(HttpStatus.UNAUTHORIZED, ex);
    }

    @ExceptionHandler(ExternalServiceException.class)
    public ProblemDetail handleExternalService(ExternalServiceException ex) {
        // Part 4.3 允許 502 或 503；這裡固定用 503（服務暫時不可用），語意上比 502（代我們轉發的閘道本身出錯）更貼近逾時情境
        return buildProblemDetail(HttpStatus.SERVICE_UNAVAILABLE, ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail problem = ProblemDetailFactory.create(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "輸入驗證失敗");
        Map<String, String> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fieldError -> fieldError.getDefaultMessage() == null ? "" : fieldError.getDefaultMessage(),
                        (existing, replacement) -> existing));
        problem.setProperty("fieldErrors", fieldErrors);
        return problem;
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return buildProblemDetail(HttpStatus.CONFLICT, ex);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        // Part 8.3：Order 的 @Version 樂觀鎖被觸發時，Hibernate/Spring 丟的是這個框架例外，
        // 不屬於我們自訂的 BaseException 階層，所以在這裡直接處理，不特地包一層自訂例外類別
        // ——這個情境本質上就是「併發衝突」，用 Spring 既有的、語意明確的例外類型就夠了，沒有必要疊床架屋。
        return ProblemDetailFactory.create(
                HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_CONFLICT", "資料已被其他請求同時修改，請重新讀取最新版本後再試");
    }

    private ProblemDetail buildProblemDetail(HttpStatus status, BaseException ex) {
        return ProblemDetailFactory.create(status, ex.getErrorCode(), ex.getMessage());
    }
}
