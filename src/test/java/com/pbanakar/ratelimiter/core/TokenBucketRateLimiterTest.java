package com.pbanakar.ratelimiter.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

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
}
