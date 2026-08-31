package com.tradebeyond.api.repository;

import com.tradebeyond.api.entity.Order;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    List<Order> findByUserUserId(Long userId);
}
