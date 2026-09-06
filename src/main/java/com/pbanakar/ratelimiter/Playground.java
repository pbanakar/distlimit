package com.pbanakar.ratelimiter;

import com.pbanakar.ratelimiter.core.RateLimiter;
import com.pbanakar.ratelimiter.core.SlidingWindowRateLimiter;
import com.pbanakar.ratelimiter.core.TokenBucketRateLimiter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interactive playground to experiment with both rate-limiting algorithms.
 *
 * <p>Run with:
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass="com.pbanakar.ratelimiter.Playground"
 * </pre>
 *
 * <p>Try changing the constructor parameters (capacity, refillRate, maxRequests,
 * windowSizeMillis) and the sleep durations to see how the behaviour changes.
 */
public class Playground {

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║        DistLimit — Rate Limiter Playground      ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        tokenBucketDemo();
        slidingWindowDemo();
        multiClientDemo();
        concurrencyDemo();
    }

    /**
     * Demonstrates Token Bucket: burst, exhaustion, and refill.
     */
    private static void tokenBucketDemo() throws InterruptedException {
        System.out.println("\n── Token Bucket (capacity=3, refill=1 token/sec) ──\n");

        RateLimiter bucket = new TokenBucketRateLimiter(3, 1.0);

        // Burst: send 5 requests instantly
        System.out.println("Sending 5 requests instantly (bucket holds 3):");
        for (int i = 1; i <= 5; i++) {
            boolean allowed = bucket.tryAcquire("user-1");
            System.out.printf("  Request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }

        // Wait for refill
        System.out.println("\n⏳ Sleeping 2 seconds to let tokens refill...\n");
        Thread.sleep(2000);

        System.out.println("Sending 3 more requests after 2s refill (~2 tokens back):");
        for (int i = 6; i <= 8; i++) {
            boolean allowed = bucket.tryAcquire("user-1");
            System.out.printf("  Request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }
    }

    /**
     * Demonstrates Sliding Window Log: strict limit and window expiry.
     */
    private static void slidingWindowDemo() throws InterruptedException {
        System.out.println("\n── Sliding Window Log (max=3 requests, window=2sec) ──\n");

        RateLimiter window = new SlidingWindowRateLimiter(3, 2000);

        System.out.println("Sending 5 requests instantly (limit is 3):");
        for (int i = 1; i <= 5; i++) {
            boolean allowed = window.tryAcquire("user-1");
            System.out.printf("  Request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }

        System.out.println("\n⏳ Sleeping 2.1 seconds for window to expire...\n");
        Thread.sleep(2100);

        System.out.println("Sending 2 more requests (window has reset):");
        for (int i = 6; i <= 7; i++) {
            boolean allowed = window.tryAcquire("user-1");
            System.out.printf("  Request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }
    }

    /**
     * Demonstrates that different clients are completely independent.
     */
    private static void multiClientDemo() {
        System.out.println("\n── Multi-Client Isolation (Token Bucket, capacity=2) ──\n");

        RateLimiter limiter = new TokenBucketRateLimiter(2, 1.0);

        System.out.println("Exhausting Alice's bucket:");
        for (int i = 1; i <= 3; i++) {
            boolean allowed = limiter.tryAcquire("alice");
            System.out.printf("  Alice request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }

        System.out.println("\nBob's bucket is unaffected:");
        for (int i = 1; i <= 3; i++) {
            boolean allowed = limiter.tryAcquire("bob");
            System.out.printf("  Bob   request %d: %s%n", i, allowed ? "✅ ALLOWED" : "❌ REJECTED");
        }
    }

    /**
     * Phase 2 demo: demonstrates thread safety under concurrent access.
     * 20 threads compete for a Token Bucket with capacity=5.
     */
    private static void concurrencyDemo() throws Exception {
        System.out.println("\n── Concurrency Demo (Token Bucket, capacity=5, 20 threads) ──\n");

        int capacity = 5;
        int threadCount = 20;
        RateLimiter limiter = new TokenBucketRateLimiter(capacity, 1.0);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int threadNum = i + 1;
            futures.add(pool.submit(() -> {
                ready.countDown();
                try { go.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                boolean result = limiter.tryAcquire("contested-client");
                String status = result ? "✅ ALLOWED" : "❌ REJECTED";
                System.out.printf("  Thread %2d: %s%n", threadNum, status);
                if (result) allowed.incrementAndGet();
                else rejected.incrementAndGet();
            }));
        }

        ready.await();
        System.out.println("All " + threadCount + " threads ready. Firing simultaneously...\n");
        go.countDown();

        for (Future<?> f : futures) { f.get(); }
        pool.shutdown();

        System.out.printf("%n  Summary: %d allowed, %d rejected (capacity was %d)%n",
                allowed.get(), rejected.get(), capacity);
        System.out.println("  " + (allowed.get() == capacity ? "✅ CORRECT" : "❌ BUG — race condition!"));

        System.out.println("\n════════════════════════════════════════════════════");
        System.out.println("  Done! Try changing the numbers and re-running.");
        System.out.println("════════════════════════════════════════════════════\n");
    }
}
