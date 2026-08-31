package com.tradebeyond.api.config;

/**
 * 一次 {@link RateLimiter#tryConsume(String)} 呼叫的結果。RateLimitFilter 靠
 * limit/remainingTokens/resetEpochSeconds 組出 X-RateLimit-Limit /
 * X-RateLimit-Remaining / X-RateLimit-Reset 三個 header（CLAUDE.md Part 2.3）。
 */
public record RateLimitResult(boolean allowed, long remainingTokens, long limit, long resetEpochSeconds) {
}
