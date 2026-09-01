package com.tradebeyond.api.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只在 GlobalExceptionHandlerTest 使用的假 Controller，用來直接丟出各種例外，
 * 獨立驗證 @RestControllerAdvice 的每個分支，不依賴真正的業務 Controller。
 */
@RestController
public class ExceptionHandlerTestController {

    @GetMapping("/test/not-found")
    void notFound() {
        throw new TestNotFoundException();
    }

    @GetMapping("/test/business")
    void business() {
        throw new TestBusinessException();
    }

    @GetMapping("/test/unauthorized")
    void unauthorized() {
        throw new TestUnauthorizedException();
    }

    @GetMapping("/test/external")
    void external() {
        throw new TestExternalServiceException();
    }

    @PostMapping("/test/validate")
    void validate(@Valid @RequestBody TestRequest request) {
    }

    @GetMapping("/test/optimistic-lock")
    void optimisticLock() {
        throw new ObjectOptimisticLockingFailureException("Order", 1L);
    }

    @GetMapping("/test/conflict")
    void conflict() {
        throw new TestConflictException();
    }

    @GetMapping("/test/forbidden")
    void forbidden() {
        throw new TestForbiddenException();
    }

    record TestRequest(@NotNull Long requiredField) {
    }

    static class TestNotFoundException extends ResourceNotFoundException {
        TestNotFoundException() {
            super("TEST_NOT_FOUND", "test not found");
        }
    }

    static class TestBusinessException extends BusinessException {
        TestBusinessException() {
            super("TEST_BUSINESS", "test business error");
        }
    }

    static class TestUnauthorizedException extends UnauthorizedException {
        TestUnauthorizedException() {
            super("TEST_UNAUTHORIZED", "test unauthorized");
        }
    }

    static class TestExternalServiceException extends ExternalServiceException {
        TestExternalServiceException() {
            super("TEST_EXTERNAL", "test external service error");
        }
    }

    static class TestConflictException extends ConflictException {
        TestConflictException() {
            super("TEST_CONFLICT", "test conflict error");
        }
    }

    static class TestForbiddenException extends ForbiddenException {
        TestForbiddenException() {
            super("TEST_FORBIDDEN", "test forbidden error");
        }
    }
}
