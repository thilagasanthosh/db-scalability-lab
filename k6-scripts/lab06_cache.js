/**
 * LAB #6: Redis caching — measure DB QPS reduction
 *
 * Cold cache (first run):   k6 run --env CACHE=cold lab06_cache.js
 * Warm cache (after 30s):   k6 run --env CACHE=warm lab06_cache.js
 *
 * Watch Grafana:
 *   mysql_global_status_queries → drops sharply once cache warms up
 *   spring_cache_gets_total{result="hit"} → cache hit ratio
 *
 * The endpoint /api/customers/{id}/orders uses @Cacheable.
 * Hitting the same customerId repeatedly warms the cache.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate } from 'k6/metrics';

const cacheHitProxy = new Rate('probable_cache_hits');

export const options = {
  stages: [
    { duration: '30s', target: 100 },
    { duration: '2m',  target: 200 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    http_req_duration: ['p(95)<100'],   // should be <10ms from Redis
    http_req_failed:   ['rate<0.01'],
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
// Hot customers — small set so cache warms quickly
const HOT_RANGE = parseInt(__ENV.HOT_RANGE || '100');

export default function () {
  // Use a small customer range to maximise cache hits
  const customerId = Math.floor(Math.random() * HOT_RANGE) + 1;
  const res = http.get(
    `${BASE_URL}/api/customers/${customerId}/orders?status=SHIPPED`);

  const ok = check(res, { 'status 200': r => r.status === 200 });
  // Proxy for cache hit: response < 5ms is almost certainly served from Redis
  if (ok && res.timings.duration < 5) {
    cacheHitProxy.add(true);
  } else {
    cacheHitProxy.add(false);
  }

  sleep(0.02);
}

export function handleSummary(data) {
  const hitRate = data.metrics.probable_cache_hits?.values.rate || 0;
  console.log('\n==== LAB #6 RESULT: Redis Cache ====');
  console.log(`p95 latency        : ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)} ms`);
  console.log(`Probable cache hit : ${(hitRate * 100).toFixed(1)}%`);
  console.log('Check Grafana spring_cache_gets_total for real hit/miss numbers');
  return {};
}
