package com.tradebeyond.api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * 使用者。account 為登入識別碼，唯一；password 一律儲存 BCrypt hash，絕不存明碼。
 */
@Entity
@Table(name = "users")
@Getter
@SQLDelete(sql = "UPDATE users SET delete_at = now() WHERE user_id = ?")
@SQLRestriction("delete_at IS NULL")
public class Users {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Setter
    @Column(name = "username", nullable = false, length = 100)
    private String username;

    @Setter
    @Column(name = "account", nullable = false, unique = true, length = 100)
    private String account;

    @Setter
    @Column(name = "password", nullable = false, length = 100)
    private String password;

    @CreationTimestamp
    @Column(name = "create_at", nullable = false, updatable = false)
    private Instant createAt;

    @UpdateTimestamp
    @Column(name = "update_at", nullable = false)
    private Instant updateAt;

    @Column(name = "delete_at")
    private Instant deleteAt;
}
