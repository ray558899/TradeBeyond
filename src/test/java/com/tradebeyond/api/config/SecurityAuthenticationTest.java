package com.tradebeyond.api.config;

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
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class})
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
