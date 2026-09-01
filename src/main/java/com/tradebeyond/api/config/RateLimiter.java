package com.tradebeyond.api.config;

/**
 * 限流抽象（CLAUDE.md Part 2.3）：目前只有 {@link InMemoryRateLimiter} 這個
 * 實作，用介面包起來是為了讓 call site（RateLimitFilter）不直接依賴 Bucket4j/
 * Caffeine，未來若真的要換成分散式後端（例如 Redis）也不用動 call site——
 * 但那個分散式版本現在不做，這裡只是預留擴充點（Part 8.0 OCP）。
 */
public interface RateLimiter {

    RateLimitResult tryConsume(String key);
}
