package com.pbanakar.ratelimiter.api;

import com.pbanakar.ratelimiter.DistLimitApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full Spring Boot integration test for the REST endpoint.
 * Uses Testcontainers to spin up Redis and wires the entire app context.
 */
@SpringBootTest(classes = DistLimitApplication.class,
        properties = {
                "rate-limiter.algorithm=redis-token-bucket",
                "rate-limiter.capacity=3",
                "rate-limiter.refill-rate=0.001"
        })
@AutoConfigureMockMvc
@Testcontainers
class RateLimitControllerIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushRedis() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    @DisplayName("POST /api/v1/rate-limit/check returns 200 when allowed, 429 when exhausted")
    void checkEndpoint_allowsThenRejects() throws Exception {
        int capacity = 3;

        // First 3 requests should return 200 with {"allowed": true}
        for (int i = 0; i < capacity; i++) {
            mockMvc.perform(post("/api/v1/rate-limit/check")
                            .param("clientId", "test-client"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.allowed").value(true));
        }

        // 4th request should return 429 with {"allowed": false}
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .param("clientId", "test-client"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.allowed").value(false));
    }

    @Test
    @DisplayName("Different clientIds are independent via the REST API")
    void checkEndpoint_independentClients() throws Exception {
        // Exhaust client-A
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/rate-limit/check")
                            .param("clientId", "client-A"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .param("clientId", "client-A"))
                .andExpect(status().isTooManyRequests());

        // client-B still has full capacity
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .param("clientId", "client-B"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }
}
