package com.tradebeyond.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 純物件層級測試，不需要 Spring context——直接 new InMemoryRateLimiter(小上限)，
 * 連續呼叫 tryConsume 驗證前 N 次 allowed=true、第 N+1 次 allowed=false，
 * 以及 remainingTokens/resetEpochSeconds 的值是否合理（CLAUDE.md Part 2.3）。
 */
class InMemoryRateLimiterTest {

    @Test
    void tryConsume_allowsUpToLimit_thenRejectsWithZeroRemaining() {
        // 連續呼叫 tryConsume：上限內每次都 allowed=true 且 remainingTokens 遞減，
        // 超過上限後 allowed=false、remainingTokens 維持 0（不會變負數），resetEpochSeconds 是未來時間點
        InMemoryRateLimiter rateLimiter = new InMemoryRateLimiter(3);
        String key = "test-key";

        RateLimitResult first = rateLimiter.tryConsume(key);
        RateLimitResult second = rateLimiter.tryConsume(key);
        RateLimitResult third = rateLimiter.tryConsume(key);
        RateLimitResult fourth = rateLimiter.tryConsume(key);

        assertThat(first.allowed()).isTrue();
        assertThat(first.remainingTokens()).isEqualTo(2);
        assertThat(first.limit()).isEqualTo(3);

        assertThat(second.allowed()).isTrue();
        assertThat(second.remainingTokens()).isEqualTo(1);

        assertThat(third.allowed()).isTrue();
        assertThat(third.remainingTokens()).isEqualTo(0);

        // 第 N+1 次（超過上限）：allowed 變 false，remainingTokens 不會變成負數，維持 0
        assertThat(fourth.allowed()).isFalse();
        assertThat(fourth.remainingTokens()).isEqualTo(0);
        assertThat(fourth.limit()).isEqualTo(3);

        // resetEpochSeconds 應該是「未來」的一個時間點（bucket 重新填滿的時刻），不是 0 或過去的值
        long now = Instant.now().getEpochSecond();
        assertThat(fourth.resetEpochSeconds()).isGreaterThan(now);
    }

    @Test
    void tryConsume_differentKeys_areCountedIndependently() {
        // 上限設 1，兩個不同 key 各自都應該拿到自己的第一次配額，不會互相干擾
        InMemoryRateLimiter rateLimiter = new InMemoryRateLimiter(1);

        RateLimitResult keyA = rateLimiter.tryConsume("user:1");
        RateLimitResult keyB = rateLimiter.tryConsume("user:2");
        RateLimitResult keyASecondCall = rateLimiter.tryConsume("user:1");

        assertThat(keyA.allowed()).isTrue();
        assertThat(keyB.allowed()).isTrue();
        assertThat(keyASecondCall.allowed()).isFalse();
    }
}
