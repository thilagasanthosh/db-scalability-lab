# DB Scalability Lab

A hands-on Spring Boot project demonstrating 11 database scalability patterns with measurable before/after results.

## Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.2.5 |
| MySQL | 8.0 |
| Redis | 7 |
| HikariCP | bundled |
| k6 | latest |

---

## Quick Start

### Option A — Local MySQL only (fastest)

```bash
# 1. Create schema
mysql -uroot -proot < sql/01_schema.sql

# 2. Seed 5M orders (~5 min)
mysql -uroot -proot < sql/02_seed.sql

# 3. Start the app
mvn spring-boot:run
```

Swagger UI → http://localhost:8080/swagger-ui.html

### Option B — Full Docker stack (Redis + Prometheus + Grafana)

```bash
docker compose -f docker-compose-lab.yml up -d
```

| Service | URL |
|---------|-----|
| App | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 (admin/admin) |
| Prometheus | http://localhost:9090 |

---

## Labs

### Lab #1 — Composite Index

**Problem:** A query filtering on `customer_id + status + created_at` does a full table scan (5M rows examined).

**Fix:** Add a composite index `(customer_id, status, created_at)`.

| | p95 latency | Rows examined |
|---|---|---|
| Before | ~800 ms | ~5,000,000 (full scan) |
| After | ~8 ms | ~10 (index seek) |

```sql
CREATE INDEX idx_orders_customer_status_created
  ON orders (customer_id, status, created_at);
```

```bash
k6 run k6-scripts/lab01_index.js --env ENDPOINT=bad   # before
k6 run k6-scripts/lab01_index.js --env ENDPOINT=good  # after
```

---

### Lab #2 — N+1 Queries

**Problem:** Loading orders with items fires 1 + N SELECT statements per request (1 for the order list, 1 per order for its items).

**Fix:** JOIN FETCH collapses N+1 into a single query.

| | DB queries per request | p95 latency |
|---|---|---|
| Before | 21 (1 + 20 items) | ~3,000 ms |
| After | 1 | ~180 ms |

```java
// Before
@Query("SELECT o FROM Order o WHERE o.customerId = :id")

// After
@Query("SELECT o FROM Order o JOIN FETCH o.items WHERE o.customerId = :id")
```

```bash
k6 run k6-scripts/lab02_n1.js --env ENDPOINT=n1    # before
k6 run k6-scripts/lab02_n1.js --env ENDPOINT=good  # after
```

---

### Lab #3 — Pagination (OFFSET vs Keyset)

**Problem:** `OFFSET 2000000` scans and discards 2M rows before returning your page.

**Fix:** Keyset pagination (`WHERE id < :lastId`) is a B-tree seek — same cost on page 1 and page 100,000.

| | p95 (page 1) | p95 (page 100k) |
|---|---|---|
| OFFSET | ~50 ms | ~4,500 ms |
| Keyset | ~12 ms | ~12 ms |

```sql
-- OFFSET (gets slower with depth)
SELECT * FROM orders ORDER BY id DESC LIMIT 20 OFFSET 2000000;

-- Keyset (constant cost)
SELECT * FROM orders WHERE id < :lastId ORDER BY id DESC LIMIT 20;
```

```bash
k6 run k6-scripts/lab03_pagination.js --env MODE=offset  # before
k6 run k6-scripts/lab03_pagination.js --env MODE=keyset  # after
```

---

### Lab #4 — Connection Pool Exhaustion

**Problem:** With `maximum-pool-size=10` and 50 concurrent users, requests queue waiting for a connection. p99 spikes to exactly `connectionTimeout` (3000 ms).

**Fix:** Right-size the pool. Rule: `pods × pool_size < max_connections`.

| | p99 latency | Error rate |
|---|---|---|
| Before (pool=10) | 3,000 ms | ~30% timeouts |
| After (pool=50) | ~80 ms | 0% |

```yaml
# application.yml
spring.datasource.hikari.maximum-pool-size: 50
```

```bash
k6 run k6-scripts/lab04_pool_exhaustion.js
# Watch Grafana: hikaricp_connections_pending
```

---

### Lab #5 — Batch Writes

**Problem:** Inserting 1,000 rows one at a time sends 1,000 round trips to MySQL.

**Fix:** `rewriteBatchedStatements=true` + `saveAll()` bundles them into one multi-row INSERT.

| | Insert 1,000 rows | Round trips |
|---|---|---|
| Before | ~2,800 ms | 1,000 |
| After | ~95 ms | 1 |

```
spring.datasource.url=jdbc:mysql://...?rewriteBatchedStatements=true
```

---

### Lab #6 — Redis Caching

**Problem:** Every request hits MySQL even when the data is unchanged.

**Fix:** `@Cacheable` with a short TTL. Cache miss goes to DB; cache hit skips it entirely.

| | p95 latency | DB QPS |
|---|---|---|
| Cold cache | ~50 ms | 100% |
| Warm cache | ~2 ms | ~10% (90% cache hits) |

```java
@Cacheable(value = "orders", key = "#customerId")
public List<Order> getOrdersByCustomer(Long customerId) { ... }
```

```bash
k6 run k6-scripts/lab06_cache.js  # run twice; second run hits warm cache
```

---

### Lab #7 — Read Replica Routing

**Problem:** All reads hit the primary, wasting replica capacity.

**Fix:** Route `@Transactional(readOnly=true)` to the replica via `AbstractRoutingDataSource`.

| | Primary load | Read latency |
|---|---|---|
| Before | 100% reads + writes | baseline |
| After | writes only | ~20% lower (replica is closer) |

Start with replica profile:
```bash
SPRING_PROFILES_ACTIVE=replica mvn spring-boot:run
```

---

### Lab #8 — Hot-Row Contention

**Problem:** Optimistic locking on a hot product row causes 40% HTTP 409 conflicts under 200 concurrent users.

**Fix:** Atomic `UPDATE … SET stock = stock - qty WHERE stock >= qty` — one serializable operation, no read-modify-write race.

| | Conflict rate | Throughput |
|---|---|---|
| Optimistic lock | ~40% 409s | degraded |
| Atomic UPDATE | 0% conflicts | full throughput |

```sql
-- Atomic fix
UPDATE products SET stock_quantity = stock_quantity - ?
WHERE id = ? AND stock_quantity >= ?;
```

```bash
# Reset stock before each run
mysql -uroot -proot -e "UPDATE scalabilitylab.products SET stock_quantity=100000, version=0 WHERE id=1;"

k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=bad     # optimistic
k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=atomic  # atomic
```

---

### Lab #9 — Table Partitioning

**Problem:** Purging or querying old orders requires a full-table operation as the table grows.

**Fix:** `PARTITION BY RANGE` on `created_at`. Drop old partitions in O(1) — no row-level DELETE.

| | DELETE 1M old rows | Query old range |
|---|---|---|
| Without partitions | ~45 s | slow (full scan) |
| With partitions | ~0.01 s (DROP PARTITION) | fast (partition pruning) |

```sql
ALTER TABLE orders PARTITION BY RANGE (YEAR(created_at)) (
  PARTITION p2022 VALUES LESS THAN (2023),
  PARTITION p2023 VALUES LESS THAN (2024),
  PARTITION p2024 VALUES LESS THAN (2025),
  PARTITION pfuture VALUES LESS THAN MAXVALUE
);
```

---

### Lab #10 — HPA Connection Bomb (Kubernetes)

**Problem:** HPA scales from 1 → 8 pods under load. Each pod opens its own connection pool. MySQL `max_connections` is breached.

**Fix:** ProxySQL in front of MySQL. Pods connect to ProxySQL; ProxySQL holds a fixed pool to MySQL regardless of pod count.

| | Pods | Connections to MySQL |
|---|---|---|
| Without ProxySQL | 1 → 8 | 10 → 80 (unbounded) |
| With ProxySQL | 1 → 8 | fixed 20 |

```bash
# Requires minikube
minikube start --memory=8192 --cpus=4
eval $(minikube docker-env)
mvn package -DskipTests
docker build -t db-scalability-lab:latest .
kubectl apply -f k8s/minikube/

k6 run k6-scripts/lab10_hpa_connection_bomb.js \
  --env BASE_URL=$(minikube service app-service --url)
```

---

### Lab #11 — Async Write Offload

**Problem:** Synchronous writes block the HTTP thread for the full DB round trip.

**Fix:** Offload to a `@Async` queue. HTTP returns 202 immediately; the write happens in a background thread pool.

| | HTTP response time | Write latency |
|---|---|---|
| Synchronous | ~120 ms (includes DB) | immediate |
| Async | ~2 ms (queue only) | ~120 ms (background) |

```java
@Async
public CompletableFuture<Order> saveAsync(Order order) { ... }
```

```bash
k6 run k6-scripts/lab11_async_write.js
```

---

## Full Benchmark

Run after all fixes are applied:

```bash
k6 run k6-scripts/full_benchmark.js
```

Expected results (p95):

| Endpoint | Before | After |
|----------|--------|-------|
| Orders — full scan | ~800 ms | ~8 ms |
| Orders — N+1 | ~3,000 ms | ~180 ms |
| Pagination OFFSET deep | ~4,500 ms | — |
| Pagination Keyset | — | ~12 ms |
| Stock — optimistic | 40% 409s | — |
| Stock — atomic | — | 0% conflicts |
| Orders — warm cache | ~50 ms | ~2 ms |

---

## Grafana Metrics

| Panel | Metric | What to look for |
|-------|--------|-----------------|
| DB QPS | `mysql_global_status_queries` | N+1 multiplies this |
| Active connections | `hikaricp_connections_active` | Pool exhaustion |
| Pending connections | `hikaricp_connections_pending` | > 0 = pool saturated |
| Slow queries | `mysql_global_status_slow_queries` | Missing index |
| Buffer pool hit ratio | Calculated from innodb metrics | Should be > 99% |
| Cache hit ratio | `spring_cache_gets_total{result=hit}` | Redis working |
| Replication lag | `mysql_slave_seconds_behind_master` | Replica falling behind |

---

## Project Structure

```
db-scalability-lab/
├── src/main/java/com/lab/dbscale/
│   ├── controller/          # OrderController, ProductController, LabController
│   ├── service/             # Business logic + @Async, @Cacheable
│   ├── repository/          # Spring Data JPA repositories
│   ├── config/              # HikariCP, Redis, read-replica routing, OpenAPI
│   └── model/               # Order, OrderItem, Product, Customer
├── src/main/resources/
│   ├── static/index.html    # Embedded lab guide (browser UI)
│   └── application.yml
├── sql/
│   ├── 01_schema.sql        # DDL
│   ├── 02_seed.sql          # 5M orders seed
│   └── 03_lab_fixes.sql     # Index + other fixes
├── k6-scripts/              # Load test scripts for each lab
├── k8s/minikube/            # Kubernetes manifests (Lab #10)
├── docker-compose-lab.yml   # Full local stack
└── Dockerfile
```

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 21 | `brew install temurin@21` |
| Maven | 3.9+ | `brew install maven` |
| Docker Desktop | latest | docker.com/products/docker-desktop |
| k6 | latest | `brew install k6` |
| minikube | optional | `brew install minikube` (Lab #10 only) |
