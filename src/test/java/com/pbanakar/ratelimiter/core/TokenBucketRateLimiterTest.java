package com.pbanakar.ratelimiter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for {@link TokenBucketRateLimiter}.
 *
 * <p>All tests use a manually-advancing {@link java.time.Clock} so they run
 * instantly and deterministically — no {@code Thread.sleep} anywhere.
 */
class TokenBucketRateLimiterTest {

    /**
     * A mutable clock that can be advanced programmatically.
     * Wraps a {@link Clock#fixed} and replaces it on each advance.
     */
    private static class TestClock extends Clock {
        private Instant currentInstant;
        private final ZoneId zone;

        TestClock(Instant start) {
            this.currentInstant = start;
            this.zone = ZoneId.of("UTC");
        }

        void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this; // not needed for tests
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }

    // ---------------------------------------------------------------
    // Test 1: First request from a new client is always allowed
    // ---------------------------------------------------------------
    @Test
    @DisplayName("First request from a new client is allowed (bucket starts full)")
    void firstRequestAlwaysAllowed() {
        TestClock clock = new TestClock(Instant.now());
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0, clock);

        assertTrue(limiter.tryAcquire("client-1"),
                "The very first request should be allowed because a new bucket starts at full capacity");
    }

    // ---------------------------------------------------------------
    // Test 2: Requests rejected once capacity exhausted (no time passing)
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Requests are rejected once capacity is exhausted with no time passing")
    void rejectedWhenCapacityExhausted() {
        TestClock clock = new TestClock(Instant.now());
        int capacity = 3;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0, clock);

        // Drain the bucket — all 3 should succeed.
        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("client-1"),
                    "Request " + (i + 1) + " of " + capacity + " should be allowed");
        }

        // The next request must be rejected — no time has passed, no refill.
        assertFalse(limiter.tryAcquire("client-1"),
                "Request after capacity is exhausted should be rejected");
        assertFalse(limiter.tryAcquire("client-1"),
                "Subsequent request should also be rejected");
    }

    // ---------------------------------------------------------------
    // Test 3: After waiting for exactly 1 token to refill, exactly 1
    //         more request is allowed
    // ---------------------------------------------------------------
    @Test
    @DisplayName("After waiting for exactly 1 token refill, exactly 1 more request is allowed")
    void refillAllowsExactlyOneMoreRequest() {
        TestClock clock = new TestClock(Instant.now());
        int capacity = 2;
        double refillRate = 1.0; // 1 token per second
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRate, clock);

        // Drain the bucket.
        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("client-1"));
        }
        assertFalse(limiter.tryAcquire("client-1"), "Bucket should be empty");

        // Advance time by exactly 1 second → 1 token refilled.
        clock.advance(Duration.ofSeconds(1));

        assertTrue(limiter.tryAcquire("client-1"),
                "After 1 second at refillRate=1, exactly 1 token should be available");
        assertFalse(limiter.tryAcquire("client-1"),
                "No second token should be available — only 1 was refilled");
    }

    // ---------------------------------------------------------------
    // Test 4: Two different clientIds have independent buckets
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Different clientIds have completely independent buckets")
    void independentBucketsPerClient() {
        TestClock clock = new TestClock(Instant.now());
        int capacity = 2;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0, clock);

        // Exhaust client-A's bucket.
        assertTrue(limiter.tryAcquire("client-A"));
        assertTrue(limiter.tryAcquire("client-A"));
        assertFalse(limiter.tryAcquire("client-A"), "client-A should be exhausted");

        // client-B should be completely unaffected.
        assertTrue(limiter.tryAcquire("client-B"),
                "client-B's first request should be allowed — independent bucket");
        assertTrue(limiter.tryAcquire("client-B"),
                "client-B's second request should be allowed — independent bucket");
        assertFalse(limiter.tryAcquire("client-B"),
                "client-B should now also be exhausted");

        // Confirm client-A is still exhausted (no time passed).
        assertFalse(limiter.tryAcquire("client-A"),
                "client-A should still be exhausted");
    }

    // ---------------------------------------------------------------
    // Test 5: Tokens never exceed capacity even after a long idle period
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Tokens cap at capacity — no overflow after long idle period")
    void tokensCapAtCapacity() {
        TestClock clock = new TestClock(Instant.now());
        int capacity = 3;
        double refillRate = 10.0; // fast refill
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRate, clock);

        // Use 1 token so the bucket isn't pristine.
        assertTrue(limiter.tryAcquire("client-1"));

        // Wait a very long time — way more than enough to overflow if uncapped.
        clock.advance(Duration.ofHours(24));

        // Should be able to make exactly `capacity` requests, no more.
        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("client-1"),
                    "Request " + (i + 1) + " should be allowed (bucket capped at capacity)");
        }
        assertFalse(limiter.tryAcquire("client-1"),
                "No extra tokens should exist beyond capacity");
    }

    // ---------------------------------------------------------------
    // Bonus: fractional refill rate works correctly
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Fractional refill rate: 0.5 tokens/sec requires 2 seconds per token")
    void fractionalRefillRate() {
        TestClock clock = new TestClock(Instant.now());
        int capacity = 5;
        double refillRate = 0.5; // 1 token every 2 seconds
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, refillRate, clock);

        // Drain the bucket completely.
        for (int i = 0; i < capacity; i++) {
            assertTrue(limiter.tryAcquire("client-1"));
        }
        assertFalse(limiter.tryAcquire("client-1"));

        // After 1 second at 0.5 tokens/sec → 0.5 tokens (not enough for 1 request).
        clock.advance(Duration.ofSeconds(1));
        assertFalse(limiter.tryAcquire("client-1"),
                "0.5 tokens is not enough to serve a request");

        // After another 1 second (total 2s) → 0.5 more = 1.0 tokens.
        clock.advance(Duration.ofSeconds(1));
        assertTrue(limiter.tryAcquire("client-1"),
                "After 2 seconds at 0.5/sec, 1 token should be available");
    }

    // ===============================================================
    // Phase 2: Concurrency tests
    // ===============================================================

    /**
     * 50 threads compete for a bucket with capacity=5, same clientId.
     * Exactly 5 must succeed, 45 must be rejected.
     * <p>Repeated 20 times — race conditions are intermittent, so a single
     * run can pass by luck.
     */
    @RepeatedTest(20)
    @DisplayName("Concurrency: exactly capacity requests allowed when N threads compete")
    void concurrentSameClient_exactlyCapacityAllowed() throws Exception {
        int capacity = 5;
        int threadCount = 50;
        // Use system clock — all threads hit the same instant, no refill.
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();       // signal "I'm ready"
                try { go.await(); }      // wait for the start gun
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                if (limiter.tryAcquire("shared-client")) {
                    allowed.incrementAndGet();
                }
            }));
        }

        ready.await();   // wait until all threads are staged
        go.countDown();  // fire!

        for (Future<?> f : futures) { f.get(); } // wait for all to finish
        pool.shutdown();

        assertEquals(capacity, allowed.get(),
                "Exactly " + capacity + " requests should be allowed, but got " + allowed.get());
    }

    /**
     * Two brand-new clientIds arrive concurrently from many threads each.
     * Verifies that computeIfAbsent creates independent buckets without
     * lost updates or cross-client contamination.
     */
    @RepeatedTest(20)
    @DisplayName("Concurrency: two new clients initialized concurrently get independent buckets")
    void concurrentNewClients_independentBuckets() throws Exception {
        int capacity = 3;
        int threadsPerClient = 20;
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0);

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
