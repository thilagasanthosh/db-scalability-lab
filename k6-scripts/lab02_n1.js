/**
 * LAB #2: N+1 query — compare lazy loading vs JOIN FETCH
 *
 * BAD:  k6 run --env ENDPOINT=n1   lab02_n1.js
 * GOOD: k6 run --env ENDPOINT=good lab02_n1.js
 *
 * Watch Grafana "DB QPS" panel:
 *   BAD  → QPS spikes to (requests × orders_per_customer)
 *   GOOD → QPS is equal to requests (1 query per request)
 *
 * Also enable SQL logging: logging.level.org.hibernate.SQL=DEBUG
 * and count SELECT statements per request in the console.
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const errors = new Counter('errors');

export const options = {
  stages: [
    { duration: '20s', target: 30  },
    { duration: '1m',  target: 100 },
    { duration: '20s', target: 0   },
  ],
  thresholds: {
    http_req_duration: ['p(95)<2000'],
    http_req_failed:   ['rate<0.05'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ENDPOINT = __ENV.ENDPOINT || 'n1';   // 'n1' or 'good'
const MAX_CUST = __ENV.MAX_CUST || 100000;

export default function () {
  const customerId = Math.floor(Math.random() * MAX_CUST) + 1;

  const url = ENDPOINT === 'n1'
    ? `${BASE_URL}/api/customers/${customerId}/orders/n1?status=SHIPPED`
    : `${BASE_URL}/api/customers/${customerId}/orders?status=SHIPPED`;

  const res = http.get(url, { tags: { endpoint: ENDPOINT } });

  const ok = check(res, { 'status 200': r => r.status === 200 });
  if (!ok) errors.add(1);

  sleep(0.05);
}

export function handleSummary(data) {
  console.log(`\n==== LAB #2 RESULT (endpoint=${ENDPOINT}) ====`);
  console.log(`p95: ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)} ms`);
  console.log(`RPS: ${data.metrics.http_reqs.values.rate.toFixed(1)}`);
  console.log('REMINDER: check DB QPS in Grafana — N+1 multiplies it!');
  return {};
}
