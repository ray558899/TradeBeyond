package com.tradebeyond.api.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宣告 JWT Bearer 的 OpenAPI SecurityScheme，讓 Swagger UI 顯示 Authorize 按鈕，
 * 並在受保護的業務 endpoint 旁顯示鎖頭圖示。純文件呈現層級設定，不影響
 * SecurityConfig 實際的驗證邏輯（Part 3）——那邊已經獨立驗證過是正常運作的。
 * 這裡套用成全域 SecurityRequirement，/api/auth/register、login、refresh、
 * logout 這 4 個 permitAll endpoint 各自在 AuthController 方法上加
 * @SecurityRequirements() 覆蓋掉，避免文件誤導成「要先登入才能呼叫 login」。
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_AUTH_SCHEME_NAME = "bearerAuth";

    @Bean
    public OpenAPI tradeBeyondOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(BEARER_AUTH_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH_SCHEME_NAME));
    }
}
