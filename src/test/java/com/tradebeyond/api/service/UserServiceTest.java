package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tradebeyond.api.dto.UserCreateRequest;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.DuplicateAccountException;
import com.tradebeyond.api.exception.ForbiddenAccessException;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.RefreshTokenRepository;
import com.tradebeyond.api.repository.UsersRepository;
import com.tradebeyond.api.testsupport.SecurityContextTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UsersRepository usersRepository;

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(usersRepository, orderRepository, refreshTokenRepository, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        SecurityContextTestSupport.clear();
    }

    @Test
    void getById_returnsUser_whenUserExists() {
        // 使用者存在時，getById 應直接回傳該 Users，供 OrderService 建單時設定 Order.user
        Users user = new Users();
        when(usersRepository.findById(1L)).thenReturn(Optional.of(user));

        Users result = userService.getById(1L);

        assertThat(result).isSameAs(user);
    }

    @Test
    void getById_throwsUserNotFoundException_whenUserDoesNotExist() {
        // 使用者不存在（含已軟刪除）時要丟 UserNotFoundException，讓上層轉成 404
        when(usersRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getById(99L))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void register_throwsDuplicateAccountException_whenAccountAlreadyExists() {
        // account 重複必須擋下來，不能建出兩筆同 account 的 Users（也是 uk_users_account 的業務層對應防線）
        when(usersRepository.findByAccount("duplicate")).thenReturn(Optional.of(new Users()));

        assertThatThrownBy(() -> userService.register(
                new UserCreateRequest("name", "duplicate", "password123")))
                .isInstanceOf(DuplicateAccountException.class);
    }

    @Test
    void register_hashesPasswordBeforeSaving_whenAccountIsAvailable() {
        // 密碼絕不能以明碼存進 DB，必須先經過 PasswordEncoder
        when(usersRepository.findByAccount("newaccount")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("plaintext-password")).thenReturn("hashed-value");
        when(usersRepository.save(any(Users.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Users result = userService.register(new UserCreateRequest("name", "newaccount", "plaintext-password"));

        assertThat(result.getUsername()).isEqualTo("name");
        assertThat(result.getAccount()).isEqualTo("newaccount");
        assertThat(result.getPassword()).isEqualTo("hashed-value");
    }

    @Test
    void deleteUser_revokesAllActiveRefreshTokens_forThatUser() {
        // 呼應 Part 4.4：刪除 User 要連帶處理關聯資料，Order 已經有這個行為，
        // 這次把 refresh_token 也納入同一套邏輯——不然使用者被刪除後，
        // 手上沒撤銷的 refresh token 理論上還能拿去換新的 access token。
        //
        // 這裡刻意用 bulk UPDATE（revokeAllActiveTokensForUser）而不是「查出 entity 清單、
        // 逐筆改欄位存檔」：後者在真實 DB 上，跟同一個 transaction 內 usersRepository.delete(user)
        // 的軟刪除放在一起 flush 時，會踩到 Hibernate 6 的一個已知 cascade 檢查問題，
        // 誤判成 TransientObjectException（已用 Testcontainers 實測重現，這個問題只有真實
        // Hibernate flush 才會出現，純 Mockito 完全測不出來，這裡只驗證「有沒有正確呼叫」）。
        SecurityContextTestSupport.authenticateAs(1L);
        Users user = new Users();
        ReflectionTestUtils.setField(user, "userId", 1L);
        when(usersRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orderRepository.findByUserUserId(1L)).thenReturn(List.of());

        userService.deleteUser(1L);

        ArgumentCaptor<Instant> revokedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), revokedAtCaptor.capture());
        assertThat(revokedAtCaptor.getValue()).isNotNull();
    }

    @Test
    void deleteUser_stillCallsRevoke_whenUserHasNoActiveRefreshTokens() {
        // bulk UPDATE 對 0 筆資料生效也是合法且安全的操作，不需要先查有沒有資料才決定要不要呼叫
        SecurityContextTestSupport.authenticateAs(1L);
        Users user = new Users();
        ReflectionTestUtils.setField(user, "userId", 1L);
        when(usersRepository.findById(1L)).thenReturn(Optional.of(user));
        when(orderRepository.findByUserUserId(1L)).thenReturn(List.of());
        when(refreshTokenRepository.revokeAllActiveTokensForUser(anyLong(), any(Instant.class))).thenReturn(0);

        userService.deleteUser(1L);

        verify(refreshTokenRepository).revokeAllActiveTokensForUser(eq(1L), any(Instant.class));
    }

    @Test
    void deleteUser_throwsForbiddenAccessException_whenCallerIsNotTheTargetUser() {
        // Part 2.4 IDOR：A 想刪 B 的帳號要被明確拒絕（403），比對在碰資料庫之前就先做，
        // 不該為了一個註定會被拒絕的請求還多打一次不會用到結果的查詢
        SecurityContextTestSupport.authenticateAs(1L);

        assertThatThrownBy(() -> userService.deleteUser(2L))
                .isInstanceOf(ForbiddenAccessException.class);
        verifyNoInteractions(usersRepository, orderRepository, refreshTokenRepository);
    }
}
