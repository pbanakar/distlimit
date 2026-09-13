package com.pbanakar.ratelimiter.config;

import com.pbanakar.ratelimiter.core.RateLimiter;
import com.pbanakar.ratelimiter.core.RedisTokenBucketRateLimiter;
import com.pbanakar.ratelimiter.core.SlidingWindowRateLimiter;
import com.pbanakar.ratelimiter.core.TokenBucketRateLimiter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Binds rate-limiter settings from {@code application.yml} and creates the
 * appropriate {@link RateLimiter} bean based on the configured algorithm.
 */
@Configuration
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterConfig {

    /** Algorithm to use: "token-bucket", "sliding-window", or "redis-token-bucket". */
    private String algorithm = "redis-token-bucket";

    /** Maximum tokens (Token Bucket) or max requests (Sliding Window). */
    private int capacity = 10;

    /** Tokens added per second (Token Bucket only). */
    private double refillRate = 1.0;

    /** Window size in milliseconds (Sliding Window only). */
    private long windowSizeMillis = 60_000;

    @Bean
    public RateLimiter rateLimiter(StringRedisTemplate redisTemplate) {
        return switch (algorithm) {
            case "token-bucket" -> new TokenBucketRateLimiter(capacity, refillRate);
            case "sliding-window" -> new SlidingWindowRateLimiter(capacity, windowSizeMillis);
            case "redis-token-bucket" -> new RedisTokenBucketRateLimiter(capacity, refillRate, redisTemplate);
            default -> throw new IllegalArgumentException(
                    "Unknown algorithm: " + algorithm +
                    ". Valid values: token-bucket, sliding-window, redis-token-bucket");
        };
    }

    // ── Getters and setters for @ConfigurationProperties binding ──

    public String getAlgorithm() { return algorithm; }
    public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }

    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }

    public double getRefillRate() { return refillRate; }
    public void setRefillRate(double refillRate) { this.refillRate = refillRate; }

    public long getWindowSizeMillis() { return windowSizeMillis; }
    public void setWindowSizeMillis(long windowSizeMillis) { this.windowSizeMillis = windowSizeMillis; }
}
