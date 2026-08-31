package com.tradebeyond.api.service;

import com.tradebeyond.api.entity.Order;
import com.tradebeyond.api.entity.Users;
import com.tradebeyond.api.exception.UserNotFoundException;
import com.tradebeyond.api.repository.OrderRepository;
import com.tradebeyond.api.repository.UsersRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UsersRepository usersRepository;
    private final OrderRepository orderRepository;

    public UserService(UsersRepository usersRepository, OrderRepository orderRepository) {
        this.usersRepository = usersRepository;
        this.orderRepository = orderRepository;
    }

    @Transactional(readOnly = true)
    public Users getById(Long userId) {
        return usersRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
    }

    @Transactional
    public void deleteUser(Long userId) {
        Users user = getById(userId);
        List<Order> orders = orderRepository.findByUserUserId(userId);
        orders.forEach(orderRepository::delete);
        usersRepository.delete(user);
    }
}
