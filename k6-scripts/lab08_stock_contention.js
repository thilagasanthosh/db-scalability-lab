/**
 * LAB #8: Hot-row contention — concurrent stock decrements
 *
 * BAD (optimistic lock → 409 conflicts):
 *   k6 run --env STRATEGY=bad lab08_stock_contention.js
 *
 * GOOD (atomic UPDATE — zero conflicts):
 *   k6 run --env STRATEGY=atomic lab08_stock_contention.js
 *
 * Product 1 starts with stock_quantity=100 (reset it before each run):
 *   UPDATE products SET stock_quantity=100000, version=0 WHERE id=1;
 *
 * Expected outcome:
 *   BAD    → ~30–50% 409s, many wasted round-trips
 *   ATOMIC → 0% 409s, 422s only when stock genuinely runs out
 *
 * Interview talking point:
 *  "Optimistic locking is right for low-contention. For hot rows like
 *   a flash-sale stock counter, an atomic UPDATE WHERE qty >= :qty is
 *   simpler, eliminates retries, and has lower tail latency."
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

const conflicts     = new Counter('lock_conflicts_409');
const outOfStock    = new Counter('out_of_stock_422');
const successRate   = new Rate('success_rate');

export const options = {
  scenarios: {
    hot_row: {
      executor: 'constant-vus',
      vus: 200,
      duration: '2m',
    },
  },
  thresholds: {
    lock_conflicts_409: ['count<100'],  // should be 0 for atomic strategy
    http_req_duration:  ['p(95)<500'],
  },
};

const BASE_URL  = __ENV.BASE_URL  || 'http://localhost:8080';
const STRATEGY  = __ENV.STRATEGY  || 'bad';   // 'bad' or 'atomic'
const PRODUCT   = __ENV.PRODUCT   || '1';

export default function () {
  const endpoint = STRATEGY === 'bad'
    ? `${BASE_URL}/api/products/${PRODUCT}/decrement/bad?qty=1`
    : `${BASE_URL}/api/products/${PRODUCT}/decrement/atomic?qty=1`;

  const res = http.post(endpoint, null, { tags: { strategy: STRATEGY } });

  const ok = check(res, {
    'success 200': r => r.status === 200,
  });
  successRate.add(ok);
  if (res.status === 409) conflicts.add(1);
  if (res.status === 422) outOfStock.add(1);

  sleep(0.01);
}

export function handleSummary(data) {
  console.log(`\n==== LAB #8 RESULT (strategy=${STRATEGY}) ====`);
  console.log(`p95 latency   : ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)} ms`);
  console.log(`Success rate  : ${(data.metrics.success_rate.values.rate * 100).toFixed(1)}%`);
  console.log(`409 conflicts : ${data.metrics.lock_conflicts_409?.values.count || 0}`);
  console.log(`422 out stock : ${data.metrics.out_of_stock_422?.values.count || 0}`);
  return {};
}
