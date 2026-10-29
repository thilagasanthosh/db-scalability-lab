/**
 * LAB #3: Pagination — OFFSET vs keyset
 *
 * Deep-page OFFSET: k6 run --env MODE=offset lab03_pagination.js
 * Keyset:           k6 run --env MODE=keyset lab03_pagination.js
 *
 * Deep OFFSET (page=100, size=20 → OFFSET 2000) on 5M rows is very slow.
 * Keyset stays constant regardless of position.
 *
 * Key point:
 *  "OFFSET n scans and discards n rows. Keyset uses the last seen ID as a
 *   WHERE clause, so the index seek is always at the same cost."
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const shallowTrend = new Trend('shallow_page_ms');
const deepTrend    = new Trend('deep_page_ms');

export const options = {
  stages: [
    { duration: '30s', target: 50  },
    { duration: '1m',  target: 150 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    'shallow_page_ms': ['p(95)<200'],
    'deep_page_ms':    ['p(95)<200'],   // keyset should pass; offset will fail
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MODE     = __ENV.MODE     || 'offset';  // 'offset' or 'keyset'
const MAX_CUST = parseInt(__ENV.MAX_CUST || '100000');

export default function () {
  const customerId = Math.floor(Math.random() * MAX_CUST) + 1;

  if (MODE === 'offset') {
    // Shallow page
    const shallow = http.get(
      `${BASE_URL}/api/customers/${customerId}/orders/bad?status=SHIPPED&page=0&size=20`);
    check(shallow, { 'shallow 200': r => r.status === 200 });
    shallowTrend.add(shallow.timings.duration);

    // Deep page — this is the killer
    const deep = http.get(
      `${BASE_URL}/api/customers/${customerId}/orders/bad?status=SHIPPED&page=100&size=20`);
    check(deep, { 'deep 200': r => r.status === 200 });
    deepTrend.add(deep.timings.duration);

  } else {
    // Keyset — first page (no cursor)
    const first = http.get(
      `${BASE_URL}/api/customers/${customerId}/orders/keyset?size=20`);
    check(first, { 'first 200': r => r.status === 200 });
    shallowTrend.add(first.timings.duration);

    // Simulate "deep" page by following 10 cursors
    let body;
    try { body = JSON.parse(first.body); } catch { return; }
    let lastId = body.nextCursor;
    for (let i = 0; i < 10 && lastId; i++) {
      const next = http.get(
        `${BASE_URL}/api/customers/${customerId}/orders/keyset?size=20&lastId=${lastId}`);
      try { body = JSON.parse(next.body); lastId = body.nextCursor; } catch { break; }
      deepTrend.add(next.timings.duration);
    }
  }

  sleep(0.1);
}

export function handleSummary(data) {
  console.log(`\n==== LAB #3 RESULT (mode=${MODE}) ====`);
  console.log(`Shallow page p95 : ${(data.metrics.shallow_page_ms?.values['p(95)'] || 0).toFixed(0)} ms`);
  console.log(`Deep page p95    : ${(data.metrics.deep_page_ms?.values['p(95)'] || 0).toFixed(0)} ms`);
  return {};
}
