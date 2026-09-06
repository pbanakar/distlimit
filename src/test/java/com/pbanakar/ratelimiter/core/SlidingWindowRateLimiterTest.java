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
 * Tests for {@link SlidingWindowRateLimiter}.
 *
 * <p>All tests use a manually-advancing {@link java.time.Clock} for
 * deterministic, instant-running tests — no {@code Thread.sleep}.
 *
 * <h3>Window boundary convention</h3>
 * <p>The sliding window is defined as the half-open interval
 * {@code (now − windowSizeMillis, now]}.  A timestamp exactly equal to
 * {@code now − windowSizeMillis} is considered <em>expired</em> and is evicted.
 * Test 3 ("boundary") validates this explicitly.</p>
 */
class SlidingWindowRateLimiterTest {

    /**
     * A mutable clock that can be advanced programmatically.
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
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }

    // ---------------------------------------------------------------
    // Test 1: Exactly maxRequests allowed, then rejected
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Exactly maxRequests are allowed within the window; the next is rejected")
    void allowsExactlyMaxRequestsThenRejects() {
        TestClock clock = new TestClock(Instant.now());
        int maxRequests = 5;
        long windowMs = 10_000; // 10 seconds
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs, clock);

        for (int i = 0; i < maxRequests; i++) {
            assertTrue(limiter.tryAcquire("client-1"),
                    "Request " + (i + 1) + " of " + maxRequests + " should be allowed");
        }

        assertFalse(limiter.tryAcquire("client-1"),
                "Request " + (maxRequests + 1) + " should be rejected — limit reached");
        assertFalse(limiter.tryAcquire("client-1"),
                "Further requests should also be rejected");
    }

    // ---------------------------------------------------------------
    // Test 2: After the window fully elapses, the client can request again
    // ---------------------------------------------------------------
    @Test
    @DisplayName("After the window fully elapses, requests are allowed again")
    void requestsAllowedAfterWindowElapses() {
        TestClock clock = new TestClock(Instant.now());
        int maxRequests = 3;
        long windowMs = 5_000; // 5 seconds
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs, clock);

        // Exhaust the limit.
        for (int i = 0; i < maxRequests; i++) {
            assertTrue(limiter.tryAcquire("client-1"));
        }
        assertFalse(limiter.tryAcquire("client-1"), "Limit should be reached");

        // Advance time so the entire window has passed.
        clock.advance(Duration.ofMillis(windowMs));

        // All previous timestamps are now exactly at the boundary and should
        // be evicted (our window is left-exclusive).
        assertTrue(limiter.tryAcquire("client-1"),
                "After the full window elapses, request should be allowed again");
    }

    // ---------------------------------------------------------------
    // Test 3: Boundary — a request exactly at the window edge is evicted
    //
    // Design decision: the window is (now - windowSizeMillis, now].
    // A timestamp exactly equal to the boundary is OUTSIDE the window.
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Boundary: timestamp exactly at window edge is evicted (left-exclusive window)")
    void boundaryTimestampIsEvicted() {
        Instant start = Instant.ofEpochMilli(100_000); // deterministic start
        TestClock clock = new TestClock(start);
        int maxRequests = 1;
        long windowMs = 1_000; // 1 second
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs, clock);

        // t=100_000: allow 1 request → log = [100_000]
        assertTrue(limiter.tryAcquire("client-1"), "First request at t=100000 should be allowed");

        // t=100_000: limit reached
        assertFalse(limiter.tryAcquire("client-1"), "Limit of 1 reached at t=100000");

        // Advance to exactly t=101_000.
        // Boundary = 101_000 - 1_000 = 100_000.
        // The timestamp 100_000 is <= boundary, so it should be evicted.
        clock.advance(Duration.ofMillis(windowMs));

        assertTrue(limiter.tryAcquire("client-1"),
                "At t=101000, the request from t=100000 is exactly at the boundary and " +
                        "should be evicted (left-exclusive window), freeing a slot");
    }

    /**
     * Complementary boundary test: advancing by windowMs − 1 should NOT evict
     * the timestamp (it's still strictly inside the window).
     */
    @Test
    @DisplayName("Boundary: timestamp 1ms inside the window is NOT evicted")
    void timestampJustInsideWindowIsKept() {
        Instant start = Instant.ofEpochMilli(100_000);
        TestClock clock = new TestClock(start);
        int maxRequests = 1;
        long windowMs = 1_000;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs, clock);

        assertTrue(limiter.tryAcquire("client-1"));

        // Advance to t=100_999. Boundary = 100_999 - 1_000 = 99_999.
        // Timestamp 100_000 > 99_999, so it's inside the window → NOT evicted.
        clock.advance(Duration.ofMillis(windowMs - 1));

        assertFalse(limiter.tryAcquire("client-1"),
                "At t=100999, the request from t=100000 is still within the window — slot should NOT be free");
    }

    // ---------------------------------------------------------------
    // Test 4: Independent state per clientId
    // ---------------------------------------------------------------
    @Test
    @DisplayName("Different clientIds have independent state")
    void independentStatePerClient() {
        TestClock clock = new TestClock(Instant.now());
        int maxRequests = 2;
        long windowMs = 10_000;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs, clock);

        // Exhaust client-A.
        assertTrue(limiter.tryAcquire("client-A"));
        assertTrue(limiter.tryAcquire("client-A"));
        assertFalse(limiter.tryAcquire("client-A"), "client-A should be exhausted");

        // client-B is unaffected.
        assertTrue(limiter.tryAcquire("client-B"),
                "client-B's first request should be allowed — independent state");
        assertTrue(limiter.tryAcquire("client-B"),
                "client-B's second request should be allowed — independent state");
        assertFalse(limiter.tryAcquire("client-B"),
                "client-B should now be exhausted");

        // client-A is still exhausted (no time passed).
        assertFalse(limiter.tryAcquire("client-A"),
                "client-A should still be exhausted");
    }

    // ===============================================================
    // Phase 2: Concurrency tests
    // ===============================================================

    /**
     * 50 threads compete for a limiter with maxRequests=5, same clientId.
     * Exactly 5 must succeed, 45 must be rejected.
     * <p>Repeated 20 times — race conditions are intermittent, so a single
     * run can pass by luck.
     */
    @RepeatedTest(20)
    @DisplayName("Concurrency: exactly maxRequests allowed when N threads compete")
    void concurrentSameClient_exactlyMaxRequestsAllowed() throws Exception {
        int maxRequests = 5;
        int threadCount = 50;
        long windowMs = 60_000; // 60s window — won't expire during the test
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs);

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

        assertEquals(maxRequests, allowed.get(),
                "Exactly " + maxRequests + " requests should be allowed, but got " + allowed.get());
    }

    /**
     * Two brand-new clientIds arrive concurrently from many threads each.
     * Verifies that computeIfAbsent creates independent deques without
     * lost updates or cross-client contamination.
     */
    @RepeatedTest(20)
    @DisplayName("Concurrency: two new clients initialized concurrently get independent state")
    void concurrentNewClients_independentState() throws Exception {
        int maxRequests = 3;
        long windowMs = 60_000;
        int threadsPerClient = 20;
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(maxRequests, windowMs);

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

        assertEquals(maxRequests, allowedA.get(),
                "client-A should get exactly " + maxRequests + " requests, got " + allowedA.get());
        assertEquals(maxRequests, allowedB.get(),
                "client-B should get exactly " + maxRequests + " requests, got " + allowedB.get());
    }
}
