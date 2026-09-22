# Contributing to DistLimit

## Prerequisites

- **Java 21** (JDK, not just JRE)
- **Maven 3.9+**
- **Docker** (for integration tests and the full stack)

## Running Tests

```bash
# Unit tests only — no Docker needed, runs in < 1 second
mvn test

# Unit + integration tests — needs Docker running
mvn verify
```

## Running the Full Stack

```bash
# Build and start Redis + 3 app instances + Nginx load balancer
docker compose up -d --build

# Verify all containers are healthy
docker compose ps

# Hit the endpoint
curl -X POST "http://localhost:9090/api/v1/rate-limit/check?clientId=test"

# Tear down
docker compose down
```

## Running the Load Test

```bash
# Start the stack first, then:
docker compose exec redis redis-cli FLUSHALL

# Distributed test (Nginx → 3 instances)
docker run --rm --network distlimit_default \
  -v "${PWD}/loadtest:/scripts" \
  -e TARGET_URL=http://nginx:80 \
  -e CLIENT_ID=test \
  grafana/k6:latest run /scripts/load-test.js
```

## Phase Structure

| Phase | What it added |
|-------|--------------|
| 1 | Token Bucket + Sliding Window algorithms, pluggable Clock, unit tests |
| 2 | Thread-safety: ConcurrentHashMap, fine-grained per-client locking, concurrency tests |
| 3 | Redis Lua script for cross-process atomicity, Spring Boot REST API, Testcontainers ITs |
| 4 | Dockerfile, docker-compose (3 replicas + Nginx), k6 load test proving distributed correctness |
| 5 | docs/ restructure, fail-open resilience, CONTRIBUTING.md, production polish |

## Commit Messages

Use conventional commit prefixes:

- `feat:` — new feature or behavior change
- `fix:` — bug fix
- `docs:` — documentation only
- `test:` — adding or modifying tests
- `refactor:` — code change that doesn't add features or fix bugs
- `chore:` — build, CI, or dependency changes

Example: `feat: add fail-open behavior on Redis outage`
