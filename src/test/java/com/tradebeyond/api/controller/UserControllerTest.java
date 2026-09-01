package com.tradebeyond.api.controller;

import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tradebeyond.api.config.InMemoryRateLimiter;
import com.tradebeyond.api.config.JwtAuthenticationEntryPoint;
import com.tradebeyond.api.config.JwtAuthenticationFilter;
import com.tradebeyond.api.config.RateLimitFilter;
import com.tradebeyond.api.config.SecurityConfig;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.service.TokenService;
import com.tradebeyond.api.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 這裡只測業務邏輯，不測認證本身——認證行為由 SecurityAuthenticationTest 獨立驗證，
 * 用 addFilters = false 讓真正的 SecurityConfig filter chain 不生效。
 */
@WebMvcTest(controllers = UserController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class, TokenService.class,
        RateLimitFilter.class, InMemoryRateLimiter.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "JWT_SECRET=test-jwt-secret-for-controller-test-0123456789")
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @Test
    void deleteUser_returns204_whenUserExists() throws Exception {
        mockMvc.perform(delete("/api/user/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_returns404WithErrorCode_whenUserDoesNotExist() throws Exception {
        // 使用者不存在（含已軟刪除）時 DELETE 必須回 404，不能靜默成功
        doThrow(new UserNotFoundException(99L)).when(userService).deleteUser(99L);

        mockMvc.perform(delete("/api/user/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
    }
}
