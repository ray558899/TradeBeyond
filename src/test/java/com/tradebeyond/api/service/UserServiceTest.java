package com.tradebeyond.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.UsersRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UsersRepository usersRepository;

    @Mock
    private OrderRepository orderRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(usersRepository, orderRepository);
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
}
