package com.tradebeyond.api.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.config.JwtAuthenticationEntryPoint;
import com.tradebeyond.api.config.JwtAuthenticationFilter;
import com.tradebeyond.api.config.SecurityConfig;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.exception.ProductNotFoundException;
import com.tradebeyond.api.service.ProductService;
import com.tradebeyond.api.service.TokenService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 這裡只測業務邏輯（找不到回 404、正常回 200 等），不測認證本身——
 * 認證行為由 SecurityAuthenticationTest 獨立驗證，所以用 addFilters = false
 * 讓真正的 SecurityConfig filter chain 不生效，維持「不用帶 token 也能測」的原意。
 * SecurityConfig 的 @Bean 方法需要注入 JwtAuthenticationFilter，就算 filter 不生效，
 * context 啟動時還是要能組出這個 bean，所以一併 import。
 */
@WebMvcTest(controllers = ProductController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-controller-test-0123456789")
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    @Test
    void getProduct_returns200WithProductBody_whenProductExists() throws Exception {
        ProductCategory category = new ProductCategory();
        ReflectionTestUtils.setField(category, "categoryId", 10L);

        Product product = new Product();
        ReflectionTestUtils.setField(product, "productId", 1L);
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));

        when(productService.getById(1L)).thenReturn(product);

        mockMvc.perform(get("/api/product/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.productCategoryId").value(10))
                .andExpect(jsonPath("$.unitPrice").value(100.0000));
    }

    @Test
    void getProduct_returns404WithErrorCode_whenProductDoesNotExist() throws Exception {
        // 找不到商品（含已軟刪除）時必須回 404 + errorCode，不能回 500
        when(productService.getById(99L)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(get("/api/product/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("PRODUCT_NOT_FOUND"));
    }
}
