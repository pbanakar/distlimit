package com.pbanakar.ratelimiter.core;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collections;

/**
 * Redis-backed Token Bucket rate limiter (Phase 3).
 *
 * <h3>Why this class exists</h3>
 * <p>The in-memory {@link TokenBucketRateLimiter} uses {@code ConcurrentHashMap}
 * and {@code synchronized} blocks for thread safety within a single JVM. But when
 * you run multiple service replicas behind a load balancer, each JVM has its own
 * separate map — client X could get 5 tokens from replica A AND 5 tokens from
 * replica B, doubling the intended limit. This class solves that by storing all
 * state in Redis, shared across all replicas.</p>
 *
 * <h3>Why GET-then-SET from Java is unsafe</h3>
 * <p>A naive approach would be:
 * <pre>
 *   double tokens = redis.get("client:tokens");  // Step 1: READ
 *   tokens = refill(tokens);                     // Step 2: COMPUTE in Java
 *   if (tokens >= 1) {
 *       redis.set("client:tokens", tokens - 1);  // Step 3: WRITE
 *       return true;
 *   }
 * </pre>
 * Between Steps 1 and 3, another replica can execute its own Step 1 and read
 * the SAME old token count. Both replicas think a token is available, both
 * decrement, but only one decrement is persisted — the other is silently lost.
 * This is a classic <strong>check-then-act race condition</strong>, identical
 * to the one Phase 2 solved with {@code synchronized}, but now across the
 * network where JVM-level locks can't help.</p>
 *
 * <h3>Why Lua scripting fixes it</h3>
 * <p>Redis executes each Lua script <strong>atomically and single-threaded</strong>.
 * While our script runs, no other Redis command (from any client, any replica) can
 * interleave. By putting the entire read-refill-check-decrement logic into a Lua
 * script executed via {@code EVAL}, we get the same atomicity as a database
 * transaction, but at Redis speed (~0.1ms per call).</p>
 *
 * @see TokenBucketRateLimiter the in-memory equivalent
 * @see RateLimiter
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "rate_limit:token_bucket:";

    private final int capacity;
    private final double refillRate;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> luaScript;

    /**
     * Creates a new Redis-backed Token Bucket rate limiter.
     *
     * @param capacity      maximum tokens per bucket
     * @param refillRate    tokens added per second
     * @param clock         time source (injected for testability)
     * @param redisTemplate Spring Redis template for executing commands
     */
    public RedisTokenBucketRateLimiter(int capacity, double refillRate,
                                       Clock clock, StringRedisTemplate redisTemplate) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        }
        if (refillRate <= 0) {
            throw new IllegalArgumentException("refillRate must be > 0, got " + refillRate);
        }
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.clock = clock;
        this.redisTemplate = redisTemplate;
        this.luaScript = loadLuaScript();
    }

    /**
     * Convenience constructor using the system UTC clock.
     */
    public RedisTokenBucketRateLimiter(int capacity, double refillRate,
                                       StringRedisTemplate redisTemplate) {
        this(capacity, refillRate, Clock.systemUTC(), redisTemplate);
    }

    private DefaultRedisScript<Long> loadLuaScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        try {
            ClassPathResource resource = new ClassPathResource("scripts/token_bucket.lua");
            String lua = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            script.setScriptText(lua);
            script.setResultType(Long.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load token_bucket.lua from classpath", e);
        }
        return script;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Executes the Token Bucket Lua script atomically on Redis.
     * The script handles read-refill-check-decrement as a single atomic operation.
     */
    @Override
    public boolean tryAcquire(String clientId) {
        String key = KEY_PREFIX + clientId;
        long nowMs = clock.instant().toEpochMilli();

        // EVAL executes the Lua script atomically on the Redis server.
        // All arguments are passed as strings; the script parses them with tonumber().
        Long result = redisTemplate.execute(
                luaScript,
                Collections.singletonList(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(nowMs)
        );

        return result != null && result == 1L;
    }
}
