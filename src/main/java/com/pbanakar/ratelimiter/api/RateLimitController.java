package com.pbanakar.ratelimiter.api;

import com.pbanakar.ratelimiter.core.RateLimiter;
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

    private final RateLimiter rateLimiter;

    public RateLimitController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Checks whether the given client is allowed to make a request.
     *
     * @param clientId client identifier (API key, IP, etc.)
     * @return 200 OK with {@code {"allowed": true}} if within limits;
     *         429 Too Many Requests with {@code {"allowed": false}} otherwise
     */
    @PostMapping("/check")
    public ResponseEntity<Map<String, Boolean>> checkRateLimit(
            @RequestParam String clientId) {

        boolean allowed = rateLimiter.tryAcquire(clientId);

        HttpStatus status = allowed ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status)
                .body(Map.of("allowed", allowed));
    }
}
