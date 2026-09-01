package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tradebeyond.api.dto.LoginRequest;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.InvalidCredentialsException;
import com.tradebeyond.api.repository.UsersRepository;
import com.tradebeyond.api.testsupport.SecurityContextTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 「已軟刪除的使用者即使密碼正確也不能登入」是 Users Entity 的 @SQLRestriction("delete_at IS NULL")
 * 在 findByAccount 查詢時自動生效的結果，屬於真實 DB 行為，Mockito mock repository 驗證不到，
 * 所以這裡用 Testcontainers 打真的 PostgreSQL。
 */
@SpringBootTest(properties = "JWT_SECRET=test-jwt-secret-for-integration-tests")
@Testcontainers
class AuthServiceIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18.3");

    @Autowired
    private UsersRepository usersRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AuthService authService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void tearDown() {
        SecurityContextTestSupport.clear();
    }

    @Test
    void login_throwsInvalidCredentialsException_whenUserIsSoftDeleted() {
        Users user = new Users();
        user.setUsername("test-user");
        user.setAccount("test-account-" + System.nanoTime());
        user.setPassword(passwordEncoder.encode("correct-password"));
        user = usersRepository.save(user);

        String account = user.getAccount();
        // Part 2.4 IDOR：deleteUser 現在會比對目前登入者，這裡是自己刪自己的帳號，先模擬登入身分
        SecurityContextTestSupport.authenticateAs(user.getUserId());
        userService.deleteUser(user.getUserId()); // 軟刪除

        // 密碼完全正確，但帳號已被軟刪除，必須跟「帳號不存在」丟出同一種例外，不能外洩「帳號存在但被刪除」
        assertThatThrownBy(() -> authService.login(new LoginRequest(account, "correct-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }
}
