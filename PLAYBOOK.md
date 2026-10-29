# Database Scalability Lab — Hands-On Playbook

## Prerequisites

| Tool | Install |
|------|---------|
| Java 21 | `brew install temurin@21` |
| Maven | `brew install maven` |
| Docker Desktop | docker.com/products/docker-desktop |
| k6 | `brew install k6` |
| MySQL client | Already have local MySQL |
| minikube (optional) | `brew install minikube` |

---

## PHASE 1 — Local MySQL only (fastest start)

### Step 1: Create the schema

```bash
mysql -uroot -proot < sql/01_schema.sql
```

### Step 2: Seed 5M orders (takes ~5 min)

```bash
mysql -uroot -proot < sql/02_seed.sql
```

### Step 3: Start the app

```bash
mvn spring-boot:run
# Watch the console — slow queries will print with logging.level.org.hibernate.SQL=DEBUG
```

---

## PHASE 2 — Docker Compose (adds Redis, replica, Prometheus, Grafana)

```bash
cd docker
docker compose up -d

# Check replication is working:
docker exec mysql-replica mysql -uroot -proot -e "SHOW REPLICA STATUS\G" | grep Running
```

Grafana → http://localhost:3000 (admin/admin)
Prometheus → http://localhost:9090

---

## LAB SEQUENCE — do these in order

### LAB #1 — Index impact

```bash
# BASELINE (full table scan)
k6 run k6-scripts/lab01_index.js --env ENDPOINT=bad

# EXPECTED: p95 ~2000ms, rows_examined = 5M in slow log

# Apply the fix:
mysql -uroot -proot scalabilitylab < sql/03_lab_fixes.sql  # first block only

# RE-MEASURE
k6 run k6-scripts/lab01_index.js --env ENDPOINT=good

# EXPECTED: p95 < 50ms
```

**Interview quote:** *"EXPLAIN showed type=ALL, 5 million rows examined.
Composite index on (customer_id, status, created_at) brought it to type=ref,
50 rows examined, p95 from 1.8s to 40ms."*

---

### LAB #2 — N+1 queries

```bash
# BAD
k6 run k6-scripts/lab02_n1.js --env ENDPOINT=n1

# Watch logs — you'll see 1 + N SELECT statements per request

# GOOD
k6 run k6-scripts/lab02_n1.js --env ENDPOINT=good

# 1 SELECT with JOIN FETCH
```

**Interview quote:** *"Spring's lazy loading fired one SELECT per order item.
With 20 orders per page, each request sent 21 queries. JOIN FETCH collapsed
it to 1. DB QPS dropped from 2000 to 100."*

---

### LAB #3 — Pagination

```bash
# OFFSET — shallow vs deep
k6 run k6-scripts/lab03_pagination.js --env MODE=offset

# KEYSET — constant cost
k6 run k6-scripts/lab03_pagination.js --env MODE=keyset
```

**Interview quote:** *"OFFSET 2,000,000 scans and discards 2M rows.
Keyset uses WHERE id < :lastId — the B-tree seek is the same cost on page 1
and page 100,000."*

---

### LAB #4 — Pool exhaustion

```bash
# Make sure maximum-pool-size=10 (default in application.yml)
k6 run k6-scripts/lab04_pool_exhaustion.js

# Watch Grafana: hikaricp_connections_pending → error rate spikes

# Fix: increase maximum-pool-size to 50 in application.yml, restart app
```

**Interview quote:** *"Pool exhaustion masquerades as slow DB. The p99 jumped
to exactly 3000ms — our connectionTimeout setting. Grafana showed
hikaricp_connections_pending=40 while active=10."*

---

### LAB #6 — Redis caching

```bash
# First run — cold cache
k6 run k6-scripts/lab06_cache.js

# Second run — warm cache (same customers)
k6 run k6-scripts/lab06_cache.js

# Compare: p95 drops from ~50ms to ~2ms, DB QPS drops 90%
```

**Trade-off to discuss:** *"Cache brings stale reads. The order list can be
2 minutes stale — acceptable for most UIs. For stock counts I'd use a
30-second TTL or read-through with a version check."*

---

### LAB #8 — Hot-row contention

```bash
# Reset stock
mysql -uroot -proot -e "UPDATE scalabilitylab.products SET stock_quantity=100000, version=0 WHERE id=1;"

# BAD — optimistic locking floods with 409s
k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=bad

# Reset stock again
mysql -uroot -proot -e "UPDATE scalabilitylab.products SET stock_quantity=100000, version=0 WHERE id=1;"

# GOOD — atomic UPDATE, zero conflicts
k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=atomic
```

**Interview quote:** *"With 200 concurrent VUs, optimistic locking produced
40% 409 conflicts. The atomic UPDATE WHERE qty >= :qty had 0% conflicts.
The difference is that optimistic locking does read-modify-write;
the atomic UPDATE is a single operation."*

---

### LAB #10 — HPA connection bomb (minikube)

```bash
# Start minikube
minikube start --memory=8192 --cpus=4

# Build image into minikube's docker daemon
eval $(minikube docker-env)
mvn package -DskipTests
docker build -t db-scalability-lab:latest .

# Deploy
kubectl apply -f k8s/minikube/mysql-statefulset.yml
kubectl apply -f k8s/minikube/deployment.yml
kubectl apply -f k8s/minikube/hpa.yml

# Install metrics-server for HPA to work
minikube addons enable metrics-server

# Get the app URL
minikube service app-service --url

# Watch HPA scale pods
kubectl get hpa -w &

# Fire load — pods will scale from 1 to 10
k6 run k6-scripts/lab10_hpa_connection_bomb.js --env BASE_URL=$(minikube service app-service --url)

# Watch: kubectl top pods
# Watch: SELECT COUNT(*) FROM information_schema.processlist (in MySQL)
```

**Interview quote:** *"HPA scaled us from 1 to 8 pods. Each pod held 10
connections. 80 connections → MySQL at 200 max_connections was 40% utilised.
The next deploy would have doubled it to 16 pods × 10 = 160 connections —
still safe, but one more scale event would breach the limit.
The fix is ProxySQL in front: pods connect to ProxySQL, ProxySQL keeps a
fixed 20-connection pool to MySQL regardless of pod count."*

---

## Full regression benchmark

```bash
# Run after ALL fixes are applied
k6 run k6-scripts/full_benchmark.js
```

Expected output after all fixes:
```
╔══════════════════════════════════════════════╗
║          FULL BENCHMARK SUMMARY (p95)       ║
╠══════════════════════════════════════════════╣
║  Endpoint           │ p95 ms                ║
║  OFFSET (bad)       │ 45                    ║  ← was 2100
║  Keyset pagination  │ 12                    ║
║  N+1 queries        │ 180                   ║  ← was 3000
║  JOIN FETCH + cache │ 4                     ║  ← Redis hit
╚══════════════════════════════════════════════╝
```

---

## Interview answer template

> "I'd start by confirming the bottleneck is the database — check p99 latency,
> the slow query log, and HikariCP pending connections. I'd fix the cheapest
> layer first:
>
> 1. **Query level** — EXPLAIN, add composite indexes, fix N+1, switch to keyset pagination.
> 2. **Application level** — tune the connection pool, batch writes with rewriteBatchedStatements.
> 3. **Caching** — Redis @Cacheable with appropriate TTLs. Accept eventual consistency.
> 4. **Read replicas** — route @Transactional(readOnly=true) to the replica pool.
> 5. **Write scale** — async writes via Kafka outbox pattern, CQRS.
> 6. **Partitioning** — PARTITION BY RANGE on date; drop old partitions.
> 7. **Sharding** — last resort; choose the shard key by access pattern.
>
> I'd prove each fix with a k6 load test against an SLO."

---

## Key Grafana panels to watch

| Panel | Metric | What to look for |
|-------|--------|-----------------|
| DB QPS | `mysql_global_status_queries` | N+1 multiplies this |
| Active connections | `hikaricp_connections_active` | Pool exhaustion |
| Pending connections | `hikaricp_connections_pending` | > 0 = pool saturated |
| Slow queries | `mysql_global_status_slow_queries` | Missing index |
| Buffer pool hit ratio | Calculated from innodb metrics | Should be > 99% |
| Cache hit ratio | `spring_cache_gets_total{result=hit}` | Redis working |
| Replication lag | `mysql_slave_seconds_behind_master` | Replica falling behind |
