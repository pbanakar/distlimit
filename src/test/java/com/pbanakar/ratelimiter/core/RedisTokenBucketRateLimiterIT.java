package com.pbanakar.ratelimiter.core;

import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link RedisTokenBucketRateLimiter}.
 *
 * <p>These tests spin up a <strong>real Redis container</strong> via Testcontainers.
 * We do NOT mock Redis — the whole point of Phase 3 is proving that the Lua script
 * executes atomically inside a real Redis server, giving us cross-process
 * consistency that JVM-level locks (Phase 2) cannot provide.</p>
 *
 * <p>Test class name ends with {@code IT} so Maven Failsafe (not Surefire) runs it
 * during {@code mvn verify}. This keeps fast Phase 1/2 unit tests separate from
 * slower container-based integration tests.</p>
 */
@Testcontainers
class RedisTokenBucketRateLimiterIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    @BeforeAll
    static void setUpRedis() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDownRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flushRedis() {
        // Clean slate for each test — prevent state leaking between tests
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    // ---------------------------------------------------------------
    // Test 1: Single client, capacity exhausted correctly
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Single client: exactly capacity requests allowed, then rejected")
    void singleClient_capacityExhausted() {
        int capacity = 5;
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(capacity, 1.0, redisTemplate);

        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("client-1"),
                    "Request " + (i + 1) + " should be allowed");
        }

        assertFalse(limiter.tryAcquire("client-1"),
                "Request after capacity is exhausted should be rejected");
        assertFalse(limiter.tryAcquire("client-1"),
                "Subsequent request should also be rejected");
    }

    // ---------------------------------------------------------------
    // Test 2: Two different clients have independent state
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Different clients have independent buckets in Redis")
    void independentClients() {
        int capacity = 2;
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(capacity, 1.0, redisTemplate);

        // Exhaust client-A
        assertTrue(limiter.tryAcquire("client-A"));
        assertTrue(limiter.tryAcquire("client-A"));
        assertFalse(limiter.tryAcquire("client-A"));

        // client-B is unaffected
        assertTrue(limiter.tryAcquire("client-B"));
        assertTrue(limiter.tryAcquire("client-B"));
        assertFalse(limiter.tryAcquire("client-B"));
    }

    // ---------------------------------------------------------------
    // Test 3: THE KEY TEST — concurrent requests, proving Lua atomicity
    //
    // This simulates 50 "service replicas" (threads) all hitting the
    // SAME Redis instance for the SAME clientId at the same instant.
    // If the Lua script weren't atomic, we'd see more than `capacity`
    // requests allowed due to check-then-act races.
    //
    // Repeated 20 times because race conditions are intermittent.
    // ---------------------------------------------------------------
    @RepeatedTest(20)
    @DisplayName("Concurrent: exactly capacity allowed when 50 threads compete on same client via Redis")
    void concurrent_sameClient_exactlyCapacityAllowed() throws Exception {
        int capacity = 5;
        int threadCount = 50;
        // Very low refill so no tokens regenerate during the test
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(capacity, 0.001, redisTemplate);

        // Flush before each repetition to reset the bucket
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryAcquire("contested-client")) {
                    allowed.incrementAndGet();
                }
            }));
        }

        ready.await();   // all threads staged
        go.countDown();  // fire simultaneously

        for (Future<?> f : futures) { f.get(); }
        pool.shutdown();

        assertEquals(capacity, allowed.get(),
                "Exactly " + capacity + " requests should be allowed via atomic Lua script, " +
                "but got " + allowed.get() + ". If this is > " + capacity +
                ", the Lua script is NOT atomic (race condition!).");
    }

    // ---------------------------------------------------------------
    // Test 4: Two new clients arriving concurrently — independent init
    // ---------------------------------------------------------------
    @RepeatedTest(20)
    @DisplayName("Concurrent: two new clients initialized concurrently get independent buckets")
    void concurrent_newClients_independent() throws Exception {
        int capacity = 3;
        int threadsPerClient = 20;
        RedisTokenBucketRateLimiter limiter =
                new RedisTokenBucketRateLimiter(capacity, 0.001, redisTemplate);

        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        CountDownLatch ready = new CountDownLatch(threadsPerClient * 2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowedA = new AtomicInteger(0);
        AtomicInteger allowedB = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadsPerClient * 2);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadsPerClient; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryAcquire("client-A")) allowedA.incrementAndGet();
            }));
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryAcquire("client-B")) allowedB.incrementAndGet();
            }));
        }

        ready.await();
        go.countDown();
        for (Future<?> f : futures) { f.get(); }
        pool.shutdown();

        assertEquals(capacity, allowedA.get(),
                "client-A should get exactly " + capacity + " tokens, got " + allowedA.get());
        assertEquals(capacity, allowedB.get(),
                "client-B should get exactly " + capacity + " tokens, got " + allowedB.get());
    }
}
