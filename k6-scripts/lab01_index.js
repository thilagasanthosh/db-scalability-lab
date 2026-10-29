/**
 * LAB #1: Index impact — full scan vs composite index
 *
 * Step 1: Run against /orders/bad (no index)
 *   k6 run --env ENDPOINT=bad lab01_index.js
 *
 * Step 2: Apply the composite index (sql/03_lab_fixes.sql)
 *   ALTER TABLE orders ADD INDEX idx_orders_customer_status_created (customer_id, status, created_at DESC);
 *
 * Step 3: Run against /orders (same endpoint but now uses the index)
 *   k6 run --env ENDPOINT=good lab01_index.js
 *
 * Compare: p95 should drop from ~2000ms to <50ms
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

const p95Trend = new Trend('custom_p95');
const errors   = new Counter('errors');

export const options = {
  stages: [
    { duration: '30s', target: 50  },   // ramp up
    { duration: '2m',  target: 200 },   // sustained load
    { duration: '30s', target: 0   },   // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],    // tighten after adding index
    http_req_failed:   ['rate<0.01'],
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const ENDPOINT  = __ENV.ENDPOINT  || 'bad';          // 'bad' or 'good' (keyset)
const MAX_CUST  = __ENV.MAX_CUST  || 100000;

export default function () {
  const customerId = Math.floor(Math.random() * MAX_CUST) + 1;
  const url = ENDPOINT === 'bad'
    ? `${BASE_URL}/api/customers/${customerId}/orders/bad?status=SHIPPED&page=0&size=20`
    : `${BASE_URL}/api/customers/${customerId}/orders?status=SHIPPED`;

  const res = http.get(url, { tags: { endpoint: ENDPOINT } });

  const ok = check(res, {
    'status 200': r => r.status === 200,
    'has orders': r => {
      try { return JSON.parse(r.body).orders !== undefined; } catch { return false; }
    },
  });
  if (!ok) errors.add(1);

  p95Trend.add(res.timings.duration);
  sleep(0.1);
}

export function handleSummary(data) {
  const p95 = data.metrics.http_req_duration.values['p(95)'];
  console.log(`\n==== LAB #1 RESULT (endpoint=${ENDPOINT}) ====`);
  console.log(`p95 latency : ${p95.toFixed(0)} ms`);
  console.log(`RPS         : ${data.metrics.http_reqs.values.rate.toFixed(1)}`);
  console.log(`Error rate  : ${(data.metrics.http_req_failed.values.rate * 100).toFixed(2)}%`);
  console.log('==============================================\n');
  return {};
}
