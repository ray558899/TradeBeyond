package com.tradebeyond.api.repository;

import com.tradebeyond.api.entity.RefreshToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 命名成 findByTokenHash 而非 findByToken：呼叫端必須先用 TokenService.hashRefreshToken(...)
    // 把使用者傳來的原始 token 算成雜湊，再拿雜湊來查——Entity 欄位本身仍叫 token（對應 DB 的 token 欄位，
    // 型態沒變，只是存進去的值從明碼變雜湊，見 V1__init_schema.sql 的註解），所以這裡用 @Query
    // 讓方法名稱可以精確表達語意，不用被 Spring Data 衍生查詢的命名規則綁死要叫 findByToken。
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.token = :tokenHash")
    Optional<RefreshToken> findByTokenHash(@Param("tokenHash") String tokenHash);

    // 供 UserService.deleteUser 撤銷該使用者名下所有還沒撤銷過的 refresh token。
    // 刻意用 bulk UPDATE 而不是「查出 entity 清單、逐筆改欄位」：後者會把 RefreshToken 載進
    // persistence context，若同一個 transaction 內還會對它的 user 關聯做 @SQLDelete 軟刪除，
    // Hibernate 6 在 flush 時對這個組合有已知的 cascade 檢查問題，會誤判成
    // TransientObjectException（見 UserService.deleteUser 的註解）。bulk UPDATE 完全不把
    // RefreshToken 載進 session，直接繞開這個問題，效能也更好（不用逐筆查詢/dirty check）。
    @Modifying
    @Query("UPDATE RefreshToken rt SET rt.revokedAt = :revokedAt WHERE rt.user.userId = :userId AND rt.revokedAt IS NULL")
    int revokeAllActiveTokensForUser(@Param("userId") Long userId, @Param("revokedAt") Instant revokedAt);
}
