package com.tradebeyond.api.config;

import com.tradebeyond.api.exception.ProblemDetailFactory;
import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.ErrorAttributes;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

/**
 * 承接 servlet 容器層級的 ERROR dispatch：完全沒有 handler 對應到的路徑（404），
 * 或任何沒被 GlobalExceptionHandler 攔到的未預期例外（通常是 500），最終都會被
 * 容器內部轉發到這裡，而不是進到 DispatcherServlet 正常的 handler 流程，
 * 所以 @RestControllerAdvice（GlobalExceptionHandler）管不到這一類錯誤（Part 4.3）。
 * Spring Boot 偵測到有自訂的 ErrorController bean 時，會自動不再建立內建的
 * BasicErrorController，不需要額外排除設定。
 *
 * @Hidden：/error 是容器內部轉發用的路徑，不是給外部呼叫方直接打的業務 endpoint，
 * 不該被 springdoc-openapi 掃描進 OpenAPI 文件、出現在 Swagger UI 的 API 列表裡；
 * 這只影響文件產生，不影響這個 controller 實際攔截 /error 請求的行為。
 */
@RestController
@Hidden
public class ApiErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorController.class);

    private final ErrorAttributes errorAttributes;

    public ApiErrorController(ErrorAttributes errorAttributes) {
        this.errorAttributes = errorAttributes;
    }

    @RequestMapping("/error")
    public ProblemDetail handleError(WebRequest webRequest) {
        Map<String, Object> attributes = errorAttributes.getErrorAttributes(webRequest, ErrorAttributeOptions.defaults());
        HttpStatus status = resolveStatus(attributes.get("status"));

        if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
            // 未預期例外的細節（可能含 SQL、內部類別名稱等）只記錄在 server 端，不回給 client，
            // 避免資訊外洩；client 只會拿到通用的 500 訊息。
            log.error("未預期的例外導致 500，path={}", attributes.get("path"), errorAttributes.getError(webRequest));
        }

        // errorCode 用 HttpStatus enum 的 name()：本身就是 reason phrase 轉大寫底線
        // （NOT_FOUND、INTERNAL_SERVER_ERROR），跟業務例外的 errorCode（例如 PRODUCT_NOT_FOUND）
        // 命名風格不同，用來標示「這是框架層級找不到 handler／未預期例外」，不是特定商業邏輯例外。
        String errorCode = status.name();
        ProblemDetail problem = ProblemDetailFactory.create(status, errorCode, defaultDetailFor(status));

        Object path = attributes.get("path");
        if (path != null) {
            problem.setInstance(URI.create(path.toString()));
        }
        return problem;
    }

    private HttpStatus resolveStatus(Object statusAttribute) {
        if (statusAttribute instanceof Integer statusCode) {
            HttpStatus resolved = HttpStatus.resolve(statusCode);
            if (resolved != null) {
                return resolved;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private String defaultDetailFor(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "找不到對應的資源路徑";
        }
        if (status.is5xxServerError()) {
            return "系統發生未預期的錯誤，請稍後再試";
        }
        return "請求無法處理";
    }
}
