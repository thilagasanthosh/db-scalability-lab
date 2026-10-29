/**
 * LAB #4: HikariCP pool exhaustion
 *
 * Default pool size = 10. This script ramps to 500 VUs.
 * At ~50 VUs you'll see:
 *   - hikaricp_connections_pending > 0
 *   - connectionTimeout errors (HTTP 500)
 *   - p99 spikes to 3000ms (connectionTimeout setting)
 *
 * Fix: in application.yml increase maximum-pool-size to match your VU count
 * (but remember: pods × pool_size < max_connections on MySQL).
 *
 * Interview talking point:
 *  "I'd increase the pool, but first profile whether transactions are
 *   long-running. A slow transaction holds a connection longer than needed."
 */
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge } from 'k6/metrics';

const timeouts = new Counter('pool_timeouts');
const errors5xx = new Counter('errors_5xx');

export const options = {
  stages: [
    { duration: '20s', target: 50  },
    { duration: '20s', target: 150 },
    { duration: '20s', target: 300 },
    { duration: '20s', target: 500 },
    { duration: '1m',  target: 500 },
    { duration: '30s', target: 0   },
  ],
  thresholds: {
    http_req_failed: ['rate<0.05'],   // intentionally soft — we WANT to see failures
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_CUST = parseInt(__ENV.MAX_CUST || '100000');

export default function () {
  const customerId = Math.floor(Math.random() * MAX_CUST) + 1;
  const res = http.get(
    `${BASE_URL}/api/customers/${customerId}/orders?status=SHIPPED`,
    { timeout: '10s' });

  const ok = check(res, { 'status 200': r => r.status === 200 });
  if (!ok) {
    if (res.status === 500) errors5xx.add(1);
    if (res.status === 0)   timeouts.add(1);
  }
}

export function handleSummary(data) {
  console.log('\n==== LAB #4 RESULT: Pool Exhaustion ====');
  console.log(`p95 latency   : ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)} ms`);
  console.log(`p99 latency   : ${data.metrics.http_req_duration.values['p(99)'].toFixed(0)} ms`);
  console.log(`5xx errors    : ${data.metrics.errors_5xx?.values.count || 0}`);
  console.log(`Pool timeouts : ${data.metrics.pool_timeouts?.values.count || 0}`);
  console.log('Check Grafana: hikaricp_connections_pending > 0 means pool is exhausted');
  console.log('Fix: increase maximum-pool-size in application.yml');
  return {};
}
