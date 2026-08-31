package com.tradebeyond.api.service;

import com.tradebeyond.api.dto.UserCreateRequest;
import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.DuplicateAccountException;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.RefreshTokenRepository;
import com.tradebeyond.api.repository.UsersRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UsersRepository usersRepository;
    private final OrderRepository orderRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UsersRepository usersRepository, OrderRepository orderRepository,
            RefreshTokenRepository refreshTokenRepository, PasswordEncoder passwordEncoder) {
        this.usersRepository = usersRepository;
        this.orderRepository = orderRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public Users getById(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Transactional
    public Users register(UserCreateRequest request) {
        usersRepository.findByAccount(request.account()).ifPresent(existing -> {
            throw new DuplicateAccountException(request.account());
        });

        Users user = new Users();
        user.setUsername(request.username());
        user.setAccount(request.account());
        user.setPassword(passwordEncoder.encode(request.password()));
        return usersRepository.save(user);
    }

    @Transactional
    public void deleteUser(Long userId) {
        Users user = getById(userId);
        List<Order> orders = orderRepository.findByUserUserId(userId);
        orders.forEach(orderRepository::delete);

        // 呼應 Part 4.4：刪除 User 要連帶處理關聯資料，refresh_token 也要一併撤銷，
        // 不然使用者被刪除後，手上沒撤銷的 refresh token 理論上還能拿去換新的 access token。
        // 這裡刻意用 bulk UPDATE（RefreshTokenRepository.revokeAllActiveTokensForUser），
        // 不要把 RefreshToken 查成 entity 清單再逐筆改欄位存檔——那個做法在同一個 transaction 內
        // 跟後面 usersRepository.delete(user) 的 @SQLDelete 軟刪除放在一起 flush 時，
        // 會踩到 Hibernate 6 的一個已知 cascade 檢查問題，誤判成 TransientObjectException
        // （已用 Testcontainers 實測重現，純 Mockito 完全測不出來，詳見 RefreshTokenRepository 的註解）。
        refreshTokenRepository.revokeAllActiveTokensForUser(userId, Instant.now());

        usersRepository.delete(user);
    }
}
