package com.pbanakar.ratelimiter.api;

import com.pbanakar.ratelimiter.core.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoint for checking rate limits.
 */
@RestController
@RequestMapping("/api/v1/rate-limit")
public class RateLimitController {

    private static final Logger log = LoggerFactory.getLogger(RateLimitController.class);

    private final RateLimiter rateLimiter;
    private final boolean failOpen;

    public RateLimitController(
            RateLimiter rateLimiter,
            @Value("${rate-limiter.fail-open:true}") boolean failOpen) {
        this.rateLimiter = rateLimiter;
        this.failOpen = failOpen;
    }

    /**
     * Checks whether the given client is allowed to make a request.
     *
     * <p><strong>Fail-open behavior:</strong> If the rate limiter backend (Redis)
     * is unreachable and {@code rate-limiter.fail-open} is {@code true}, the request
     * is allowed with a warning log. This is a deliberate production decision: a rate
     * limiter outage should not cause an API outage. Losing rate limiting temporarily
     * is the lesser evil compared to rejecting 100% of traffic because a supporting
     * service is down. The setting is configurable for environments where rate
     * enforcement is more important than availability.
     *
     * @param clientId client identifier (API key, IP, etc.)
     * @return 200 OK with {@code {"allowed": true}} if within limits;
     *         429 Too Many Requests with {@code {"allowed": false}} otherwise;
     *         200 OK if Redis is down and fail-open is enabled;
     *         500 Internal Server Error if Redis is down and fail-open is disabled
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkRateLimit(
            @RequestParam String clientId) {

        try {
            boolean allowed = rateLimiter.tryAcquire(clientId);

            HttpStatus status = allowed ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;
            return ResponseEntity.status(status)
                    .body(Map.of("allowed", allowed));
        } catch (Exception e) {
            if (failOpen) {
                log.warn("Redis unavailable — failing open, request allowed", e);
                return ResponseEntity.ok(Map.of("allowed", true));
            } else {
                log.error("Redis unavailable — failing closed, request rejected", e);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("allowed", false, "error", "Rate limiter unavailable"));
            }
        }
    }
}
