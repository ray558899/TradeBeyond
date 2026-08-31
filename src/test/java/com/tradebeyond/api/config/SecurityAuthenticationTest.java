package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.controller.ProductController;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.service.ProductService;
import com.tradebeyond.api.service.TokenService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 獨立測試「真正的」SecurityConfig + JwtAuthenticationFilter chain 是否生效——
 * 不加 addFilters = false，讓 filter chain 真的跑，才能驗證認證行為本身。
 * 挑 GET /api/product/{id} 當受保護 endpoint 的代表，業務邏輯（ProductService）整個 mock 掉，
 * 這裡只在乎「有沒有帶合法 token」這件事。
 */
@WebMvcTest(controllers = ProductController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-security-filter-test-0123456789")
class SecurityAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TokenService tokenService;

    @MockitoBean
    private ProductService productService;

    @Test
    void protectedEndpoint_returns401_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpoint_returns401AsProblemDetail_whenNoTokenProvided() throws Exception {
        // Part 4.3：filter chain 攔下的 401 也要走跟 GlobalExceptionHandler 一致的 ProblemDetail 格式，
        // client 不該從回應格式分辨出這個 401 是 filter 擋的還是應用程式邏輯丟的
        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.errorCode").exists());
    }

    @Test
    void swaggerUiEntryPage_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        // /swagger-ui.html 是 Swagger UI 的進入頁面本身，不在 /swagger-ui/** 這個 pattern
        // 涵蓋範圍內（那個是給 /swagger-ui/index.html 等底下的靜態資源用的）。這個 @WebMvcTest
        // slice 沒有註冊 springdoc 的資源 handler，所以就算通過安全層，也還是會因為「這個 slice
        // 裡沒有對應的 handler」而不是 200——這裡只在乎「安全層有沒有把它擋成 401」，不是
        // 「這個窄範圍的測試 slice 能不能完整渲染頁面」，所以只斷言不是 401，不斷言確切狀態碼。
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void swaggerUiStaticResource_underNestedPath_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        // 迴歸測試：曾經把白名單簡化成 "/swagger-ui**"（拿掉斜線，想一次涵蓋所有變體），
        // 結果 Spring 6 的 PathPatternParser 要求 "**" 必須是獨立路徑片段，這種寫法沒有跨層級效果，
        // 導致 /swagger-ui/index.html 這類「/swagger-ui.html 底下」的多層資源整組退化成 401
        // （用真實 app 手動測出來的，這個 @WebMvcTest 套件當時完全沒測到、全部綠燈）。
        // 這裡專門守住「多層路徑」這個曾經被弄壞的案例，避免未來又用類似寫法悄悄回歸。
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void openApiJsonSpec_bareUrl_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void openApiYamlSpec_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        // /v3/api-docs.yaml 是 springdoc 提供的 YAML 格式 spec，路徑直接接在 /v3/api-docs 後面
        // （沒有斜線分隔），不符合 /v3/api-docs/** 這個 pattern 的匹配規則，會被漏掉。
        // 這個 @WebMvcTest slice 沒有註冊 springdoc 自己的 OpenAPI 產生器，所以就算通過安全層，
        // 也不會是 200——這裡只在乎安全層有沒有把它擋成 401。
        mockMvc.perform(get("/v3/api-docs.yaml"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void openApiSwaggerConfig_underNestedPath_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        // 另一個「多層路徑」的例子：/v3/api-docs/swagger-config 是 swagger-ui.html 內部用來
        // 自我配置的 endpoint，同樣曾經被 "/v3/api-docs**" 這種簡化寫法弄壞過。
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void swaggerUiWebjarResource_isNotBlockedByFilterChain_whenNoTokenProvided() throws Exception {
        // springdoc-openapi-starter-webmvc-ui 透過 org.webjars:swagger-ui 依賴帶進 swagger-ui 的
        // 靜態資源，這些資源同時也能透過 Spring Boot 預設的 /webjars/** 靜態資源 handler 存取
        // （跟 springdoc 自己掛的 /swagger-ui/** 是兩條不同路徑，各自獨立），原本的白名單沒涵蓋到。
        mockMvc.perform(get("/webjars/swagger-ui/index.html"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(401));
    }

    @Test
    void protectedEndpoint_returns200_whenValidTokenProvided() throws Exception {
        ProductCategory category = new ProductCategory();
        ReflectionTestUtils.setField(category, "categoryId", 10L);
        Product product = new Product();
        ReflectionTestUtils.setField(product, "productId", 1L);
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));
        when(productService.getById(1L)).thenReturn(product);

        String token = tokenService.generateAccessToken(1L);

        mockMvc.perform(get("/api/product/1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
