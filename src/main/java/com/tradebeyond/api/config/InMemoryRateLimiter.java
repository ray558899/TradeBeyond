package com.tradebeyond.api.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bucket4j + Caffeine 的限流實作（CLAUDE.md Part 2.3）。每個 key（userId 或 IP）
 * 對應一個獨立的 Bucket4j bucket，key 第一次出現時才建立，容量／每小時補滿的數量
 * 都是同一個 app.rate-limit.requests-per-hour 設定值。
 *
 * Caffeine 的 maximumSize/expireAfterAccess 是為了 bound 記憶體用量（Part 9.1
 * Anti-OOM）——不會有無限增長的 key 集合；這兩個值目前寫死，不像
 * requests-per-hour 那樣開放成 application.yml 屬性，因為它們只是「防止 OOM
 * 的安全邊界」，不是業務決策，沒有需要依環境調整的理由。
 */
@Component
public class InMemoryRateLimiter implements RateLimiter {

    private final long requestsPerHour;
    private final Cache<String, Bucket> buckets;

    public InMemoryRateLimiter(@Value("${app.rate-limit.requests-per-hour:5000}") long requestsPerHour) {
        this.requestsPerHour = requestsPerHour;
        this.buckets = Caffeine.newBuilder()
                .maximumSize(100_000)
                .expireAfterAccess(Duration.ofHours(2))
                .build();
    }

    @Override
    public RateLimitResult tryConsume(String key) {
        Bucket bucket = buckets.get(key, unusedKey -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        long resetEpochSeconds = Instant.now().plusNanos(probe.getNanosToWaitForReset()).getEpochSecond();
        return new RateLimitResult(probe.isConsumed(), probe.getRemainingTokens(), requestsPerHour, resetEpochSeconds);
    }

    private Bucket newBucket() {
        // classic + intervally：容量在每個整點週期一次補滿（不是漸進式），符合
        // 「每小時上限 N 次」這種固定窗口的直覺語意，也讓 resetEpochSeconds 有明確意義：
        // 下一次整批補滿的時間點。
        Bandwidth limit = Bandwidth.classic(requestsPerHour, Refill.intervally(requestsPerHour, Duration.ofHours(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
