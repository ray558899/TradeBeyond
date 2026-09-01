package com.tradebeyond.api.repository;

import com.tradebeyond.api.entity.Order;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserUserId(Long userId);

    // IDOR 歸屬檢查（CLAUDE.md Part 2.4）：orderId 跟目前登入者 userId 一起當查詢條件，
    // 不是「先查出來再判斷擁有者」——訂單存在但不是自己的，跟訂單根本不存在，查出來都是空的，
    // Service 層不用、也不應該分辨這兩種情況，一律視同「找不到」。
    Optional<Order> findByOrderIdAndUserUserId(Long orderId, Long userId);
}
