package com.pbanakar.ratelimiter.config;

import com.pbanakar.ratelimiter.core.RateLimiter;
import com.pbanakar.ratelimiter.core.PolicyRateLimiter;
import com.pbanakar.ratelimiter.core.RedisTokenBucketRateLimiter;
import com.pbanakar.ratelimiter.core.SlidingWindowRateLimiter;
import com.pbanakar.ratelimiter.core.TokenBucketRateLimiter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<String, RateLimiter> configuredPolicies = new LinkedHashMap<>();
        if (policies.isEmpty()) {
            configuredPolicies.put("default", createLimiter(
                    new Policy("default", algorithm, capacity, refillRate, windowSizeMillis),
                    redisTemplate));
        } else {
            policies.forEach((name, policy) -> {
                policy.applyDefaults(name, algorithm, capacity, refillRate, windowSizeMillis);
                configuredPolicies.put(name, createLimiter(policy, redisTemplate));
            });
        }
        if (!configuredPolicies.containsKey("default")) {
            throw new IllegalArgumentException("A 'default' rate-limit policy is required");
        }
        return new PolicyRateLimiter(configuredPolicies);
    }

    private RateLimiter createLimiter(Policy policy, StringRedisTemplate redisTemplate) {
        return switch (policy.algorithm) {
            case "token-bucket" -> new TokenBucketRateLimiter(policy.capacity, policy.refillRate);
            case "sliding-window" -> new SlidingWindowRateLimiter(policy.capacity, policy.windowSizeMillis);
            case "redis-token-bucket" -> new RedisTokenBucketRateLimiter(
                    policy.capacity, policy.refillRate, redisTemplate, policy.name);
            default -> throw new IllegalArgumentException(
                    "Unknown algorithm: " + policy.algorithm +
                    ". Valid values: token-bucket, sliding-window, redis-token-bucket");
        };
    }

    private Map<String, Policy> policies = new LinkedHashMap<>();

    public Map<String, Policy> getPolicies() { return policies; }
    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies == null ? new LinkedHashMap<>() : policies;
    }

    public static class Policy {
        private String name;
        private String algorithm;
        private int capacity;
        private double refillRate;
        private long windowSizeMillis;

        public Policy() {}

        public Policy(String name, String algorithm, int capacity,
                      double refillRate, long windowSizeMillis) {
            this.name = name;
            this.algorithm = algorithm;
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.windowSizeMillis = windowSizeMillis;
        }

        private void applyDefaults(String name, String defaultAlgorithm, int defaultCapacity,
                                   double defaultRefillRate, long defaultWindowSizeMillis) {
            this.name = name;
            if (algorithm == null || algorithm.isBlank()) algorithm = defaultAlgorithm;
            if (capacity == 0) capacity = defaultCapacity;
            if (refillRate == 0) refillRate = defaultRefillRate;
            if (windowSizeMillis == 0) windowSizeMillis = defaultWindowSizeMillis;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getAlgorithm() { return algorithm; }
        public void setAlgorithm(String algorithm) { this.algorithm = algorithm; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public double getRefillRate() { return refillRate; }
        public void setRefillRate(double refillRate) { this.refillRate = refillRate; }
        public long getWindowSizeMillis() { return windowSizeMillis; }
        public void setWindowSizeMillis(long windowSizeMillis) { this.windowSizeMillis = windowSizeMillis; }
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
