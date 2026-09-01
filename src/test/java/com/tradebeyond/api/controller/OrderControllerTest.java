package com.tradebeyond.api.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradebeyond.api.config.InMemoryRateLimiter;
import com.tradebeyond.api.config.JwtAuthenticationEntryPoint;
import com.tradebeyond.api.config.JwtAuthenticationFilter;
import com.tradebeyond.api.config.RateLimitFilter;
import com.tradebeyond.api.config.SecurityConfig;
import com.tradebeyond.api.dto.OrderCreateRequest;
import com.tradebeyond.api.dto.OrderUpdateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Product;
import com.tradebeyond.api.entity.ProductCategory;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.ForbiddenAccessException;
import com.tradebeyond.api.exception.OrderNotFoundException;
import com.tradebeyond.api.service.OrderService;
import com.tradebeyond.api.service.TokenService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 這裡只測業務邏輯，不測認證本身——認證行為由 SecurityAuthenticationTest 獨立驗證，
 * 用 addFilters = false 讓真正的 SecurityConfig filter chain 不生效。
 */
@WebMvcTest(controllers = OrderController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-controller-test-0123456789")
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

    private Order buildOrder(long orderId, BigDecimal orderAmount, BigDecimal totalCost) {
        Users user = new Users();
        ReflectionTestUtils.setField(user, "userId", 1L);

        ProductCategory category = new ProductCategory();
        ReflectionTestUtils.setField(category, "categoryId", 10L);

        Product product = new Product();
        ReflectionTestUtils.setField(product, "productId", 2L);
        product.setProductCategory(category);
        product.setUnitPrice(new BigDecimal("100.0000"));

        Order order = new Order();
        ReflectionTestUtils.setField(order, "orderId", orderId);
        order.setUser(user);
        order.setProduct(product);
        order.setOrderAmount(orderAmount);
        order.setUnitPriceSnapshot(new BigDecimal("100.0000"));
        order.setTaxRateSnapshot(new BigDecimal("0.0500"));
        order.setTotalCost(totalCost);
        return order;
    }

    @Test
    void createOrder_returns201WithOrderBody_whenRequestIsValid() throws Exception {
        // 請求合法時，建立訂單應該回 201 並帶回完整的訂單資料（含後端算出的 totalCost）
        Order order = buildOrder(100L, new BigDecimal("2"), new BigDecimal("210.0000"));
        when(orderService.createOrder(any(OrderCreateRequest.class))).thenReturn(order);

        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderCreateRequest(2L, new BigDecimal("2")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderId").value(100))
                .andExpect(jsonPath("$.totalCost").value(210.0));
    }

    @Test
    void createOrder_returns400WithValidationErrorCode_whenOrderAmountIsNegative() throws Exception {
        // @Positive 驗證失敗必須回 400 + 統一的 ProblemDetail 格式，不可以是 500
        mockMvc.perform(post("/api/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderCreateRequest(2L, new BigDecimal("-1")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void patchOrder_returns200WithRecalculatedTotalCost_whenOrderExists() throws Exception {
        // 訂單存在時，PATCH 應該回 200 並帶回依新 orderAmount 重算後的 totalCost
        Order order = buildOrder(100L, new BigDecimal("5"), new BigDecimal("525.0000"));
        when(orderService.patchOrderAmount(eq(100L), any(OrderUpdateRequest.class))).thenReturn(order);

        mockMvc.perform(patch("/api/order/100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderUpdateRequest(new BigDecimal("5")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(100))
                .andExpect(jsonPath("$.totalCost").value(525.0));
    }

    @Test
    void patchOrder_returns404WithErrorCode_whenOrderDoesNotExist() throws Exception {
        // 訂單不存在（含已軟刪除）時 PATCH 必須回 404，不能靜默成功
        when(orderService.patchOrderAmount(eq(999L), any(OrderUpdateRequest.class)))
                .thenThrow(new OrderNotFoundException(999L));

        mockMvc.perform(patch("/api/order/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OrderUpdateRequest(new BigDecimal("5")))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
    }

    @Test
    void deleteOrder_returns204_whenOrderExists() throws Exception {
        // 訂單存在時，DELETE 應該回 204 No Content
        mockMvc.perform(delete("/api/order/100"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteOrder_returns404WithErrorCode_whenOrderDoesNotExist() throws Exception {
        // 訂單不存在（含已軟刪除）時 DELETE 必須回 404，不能靜默成功
        doThrow(new OrderNotFoundException(999L)).when(orderService).deleteOrder(999L);

        mockMvc.perform(delete("/api/order/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORDER_NOT_FOUND"));
    }

    @Test
    void listOrdersByUser_returns200WithOrderList_whenUserHasOrders() throws Exception {
        // 這裡回傳的清單已經是 Service 層（Phase 3）排除軟刪除後的結果，Controller 只負責轉 DTO
        Order order = buildOrder(100L, new BigDecimal("2"), new BigDecimal("210.0000"));
        when(orderService.findOrdersByUserId(1L)).thenReturn(List.of(order));

        mockMvc.perform(get("/api/order/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(100))
                .andExpect(jsonPath("$[0].totalCost").value(210.0));
    }

    @Test
    void listOrdersByUser_returns400_whenUserIdIsNotNumeric() throws Exception {
        // path variable 型別不符（非數字）要回 4xx，不能是 500
        mockMvc.perform(get("/api/order/abc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listOrdersByUser_returns403WithErrorCode_whenCallerRequestsAnotherUsersOrders() throws Exception {
        // Part 2.4 IDOR：Service 層丟出 ForbiddenAccessException，這裡驗證 Controller/GlobalExceptionHandler
        // 這條線有正確接起來，回 403 + errorCode（不是只驗證 Service 邏輯本身，那個在 OrderServiceTest 測過了）
        when(orderService.findOrdersByUserId(2L)).thenThrow(new ForbiddenAccessException("只能查詢自己的訂單"));

        mockMvc.perform(get("/api/order/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("FORBIDDEN_NOT_OWNER"));
    }
}
