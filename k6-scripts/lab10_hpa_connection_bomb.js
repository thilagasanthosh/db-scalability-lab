/**
 * LAB #10: The HPA connection bomb
 *
 * Scenario:
 *   You scale Spring Boot from 1 pod to 10 pods with HPA.
 *   Each pod has HikariCP maximum-pool-size=10.
 *   10 pods × 10 connections = 100 connections to MySQL.
 *   MySQL max_connections=200 → you're fine... until the next deploy.
 *   20 pods × 10 = 200 → MySQL starts refusing connections.
 *
 * This script simulates the moment traffic spikes and HPA fires.
 *
 * WATCH IN GRAFANA:
 *   hikaricp_connections_active{pool="PrimaryPool"}  — per pod
 *   mysql_global_status_threads_connected            — total DB connections
 *
 * Fix options:
 *   1. Shrink pool per pod: max_pool = (max_connections / expected_pods) - 5
 *   2. Add ProxySQL or RDS Proxy — pods connect to proxy, proxy maintains
 *      a smaller persistent pool to MySQL.
 *   3. PgBouncer equivalent for MySQL = ProxySQL in transaction mode.
 *
 * Run: k6 run --env PODS=1  lab10_hpa_connection_bomb.js   (baseline)
 *      k6 run --env PODS=10 lab10_hpa_connection_bomb.js   (after HPA scale-out)
 */
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const refused = new Counter('connection_refused');

export const options = {
  stages: [
    { duration: '20s', target: 100 },
    { duration: '1m',  target: 300 },
    { duration: '20s', target: 0   },
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const MAX_CUST = parseInt(__ENV.MAX_CUST || '100000');

export default function () {
  const customerId = Math.floor(Math.random() * MAX_CUST) + 1;
  const res = http.get(
    `${BASE_URL}/api/customers/${customerId}/orders?status=SHIPPED`);

  const ok = check(res, { 'status 200': r => r.status === 200 });
  if (!ok && (res.status === 500 || res.status === 0)) {
    refused.add(1);
  }
  sleep(0.05);
}

export function handleSummary(data) {
  const pods = __ENV.PODS || '?';
  console.log(`\n==== LAB #10 RESULT (simulated pods=${pods}) ====`);
  console.log(`p95 latency     : ${data.metrics.http_req_duration.values['p(95)'].toFixed(0)} ms`);
  console.log(`Connection fails: ${data.metrics.connection_refused?.values.count || 0}`);
  console.log('Check mysql_global_status_threads_connected in Grafana');
  console.log('Interview: "pods × pool_size must stay < max_connections on MySQL"');
  return {};
}
