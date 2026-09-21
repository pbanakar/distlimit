/*
 * k6 load test — proves distributed rate limiting correctness.
 *
 * WHY k6 OVER JMETER?
 * - k6 scripts are plain JavaScript — easy to read, version-control, and modify
 * - k6 runs as a single binary (or Docker container) — no GUI or XML config
 * - k6 has built-in checks and thresholds, making assertions simple
 * - JMeter is better for complex UI-driven test plans; k6 is better for
 *   developer-driven, CI-integrated load tests like this one
 *
 * TEST SCENARIO:
 * Fire TOTAL_REQUESTS simultaneous requests for the same clientId.
 * With capacity=10 and near-zero refill, exactly 10 should return 200
 * and the rest should return 429. This holds whether requests go through
 * Nginx (distributed across 3 instances) or hit a single instance directly.
 */

import http from 'k6/http';
import { Counter } from 'k6/metrics';

// Custom metrics to track allowed vs rejected
const allowedCount = new Counter('allowed_requests');
const rejectedCount = new Counter('rejected_requests');

// Each VU sends exactly 1 request, all at once (shared-iterations)
const TOTAL_REQUESTS = 50;

export const options = {
    scenarios: {
        burst: {
            executor: 'shared-iterations',
            vus: TOTAL_REQUESTS,
            iterations: TOTAL_REQUESTS,
            maxDuration: '30s',
        },
    },
    // No thresholds here — we check pass/fail in the shell script
    // by parsing the counter output
};

export default function () {
    const baseUrl = __ENV.TARGET_URL || 'http://localhost:9090';
    const clientId = __ENV.CLIENT_ID || 'load-test-client';

    const res = http.post(
        `${baseUrl}/api/v1/rate-limit/check?clientId=${clientId}`
    );

    if (res.status === 200) {
        allowedCount.add(1);
    } else if (res.status === 429) {
        rejectedCount.add(1);
    }
}
