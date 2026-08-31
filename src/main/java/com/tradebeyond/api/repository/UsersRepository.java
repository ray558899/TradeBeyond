package com.tradebeyond.api.repository;

import com.tradebeyond.api.entity.Users;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UsersRepository extends JpaRepository<Users, Long> {

    Optional<Users> findByAccount(String account);
}
