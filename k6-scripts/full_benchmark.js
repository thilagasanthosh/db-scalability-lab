/**
 * FULL BENCHMARK — runs all endpoints simultaneously.
 * Use this as an overall health check or regression test after each fix.
 *
 * k6 run full_benchmark.js
 *
 * Outputs a comparison table at the end.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend } from 'k6/metrics';

const tBad      = new Trend('p_bad_offset');
const tKeyset   = new Trend('p_keyset');
const tN1       = new Trend('p_n1');
const tJoinFetch = new Trend('p_join_fetch');

export const options = {
  stages: [
    { duration: '30s', target: 50  },
    { duration: '2m',  target: 100 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    p_bad_offset:  ['p(95)<5000'],   // will be slow without index
    p_keyset:      ['p(95)<200'],
    p_n1:          ['p(95)<3000'],   // will be slow without JOIN FETCH
    p_join_fetch:  ['p(95)<100'],    // fast with JOIN FETCH + Redis
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_CUST = parseInt(__ENV.MAX_CUST || '100000');

export default function () {
  const cid = Math.floor(Math.random() * MAX_CUST) + 1;

  const r1 = http.get(`${BASE_URL}/api/customers/${cid}/orders/bad?status=SHIPPED&page=0&size=20`);
  check(r1, { 'bad 200': r => r.status === 200 });
  tBad.add(r1.timings.duration);

  const r2 = http.get(`${BASE_URL}/api/customers/${cid}/orders/keyset?size=20`);
  check(r2, { 'keyset 200': r => r.status === 200 });
  tKeyset.add(r2.timings.duration);

  const r3 = http.get(`${BASE_URL}/api/customers/${cid}/orders/n1?status=SHIPPED`);
  check(r3, { 'n1 200': r => r.status === 200 });
  tN1.add(r3.timings.duration);

  const r4 = http.get(`${BASE_URL}/api/customers/${cid}/orders?status=SHIPPED`);
  check(r4, { 'jf 200': r => r.status === 200 });
  tJoinFetch.add(r4.timings.duration);

  sleep(0.2);
}

export function handleSummary(data) {
  const p = (name) => (data.metrics[name]?.values['p(95)'] || 0).toFixed(0);
  console.log('\n╔══════════════════════════════════════════════╗');
  console.log('║          FULL BENCHMARK SUMMARY (p95)       ║');
  console.log('╠══════════════════════════════════════════════╣');
  console.log(`║  Endpoint           │ p95 ms                ║`);
  console.log(`║  OFFSET (bad)       │ ${p('p_bad_offset').padEnd(20)} ║`);
  console.log(`║  Keyset pagination  │ ${p('p_keyset').padEnd(20)} ║`);
  console.log(`║  N+1 queries        │ ${p('p_n1').padEnd(20)} ║`);
  console.log(`║  JOIN FETCH + cache │ ${p('p_join_fetch').padEnd(20)} ║`);
  console.log('╚══════════════════════════════════════════════╝');
  return {};
}
