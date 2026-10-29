-- =============================================================================
-- LAB FIX SCRIPTS — run each AFTER measuring the baseline
-- =============================================================================

USE scalabilitylab;

-- ─────────────────────────────────────────────────────────────────────────────
-- LAB #1 FIX: Add composite index
-- Before:  EXPLAIN shows type=ALL (full scan), rows=5M
-- After:   type=ref, rows=~50 — p95 drops from ~2s to <50ms
-- ─────────────────────────────────────────────────────────────────────────────
ALTER TABLE orders
  ADD INDEX idx_orders_customer_status_created
    (customer_id, status, created_at DESC);

-- Verify the planner uses it:
EXPLAIN SELECT * FROM orders
WHERE customer_id = 1 AND status = 'SHIPPED'
ORDER BY created_at DESC
LIMIT 20;


-- ─────────────────────────────────────────────────────────────────────────────
-- LAB #5 FIX: Enable slow query log (connection-level, no restart needed)
-- ─────────────────────────────────────────────────────────────────────────────
SET GLOBAL slow_query_log    = 'ON';
SET GLOBAL long_query_time   = 0.2;    -- log queries > 200 ms
SET GLOBAL log_output        = 'TABLE';

-- Query the slow log:
-- SELECT * FROM mysql.slow_log ORDER BY start_time DESC LIMIT 20;


-- ─────────────────────────────────────────────────────────────────────────────
-- LAB #9 FIX: Range partitioning on orders.created_at
-- (Drop and re-create — adjust year ranges for your seed data)
-- ─────────────────────────────────────────────────────────────────────────────
-- Step 1: drop FK temporarily (MySQL can't partition with FKs)
-- ALTER TABLE order_items DROP FOREIGN KEY fk_items_order;
-- ALTER TABLE orders      DROP FOREIGN KEY fk_orders_customer;

-- Step 2: convert to partitioned table
-- ALTER TABLE orders
--   PARTITION BY RANGE (YEAR(created_at)) (
--     PARTITION p2022 VALUES LESS THAN (2023),
--     PARTITION p2023 VALUES LESS THAN (2024),
--     PARTITION p2024 VALUES LESS THAN (2025),
--     PARTITION p2025 VALUES LESS THAN (2026),
--     PARTITION pmax  VALUES LESS THAN MAXVALUE
--   );

-- Step 3: verify partition pruning
-- EXPLAIN SELECT * FROM orders
-- WHERE customer_id = 1 AND created_at >= '2024-01-01'
-- ORDER BY created_at DESC LIMIT 20;

-- Drop old partition (instead of slow DELETE):
-- ALTER TABLE orders DROP PARTITION p2022;


-- ─────────────────────────────────────────────────────────────────────────────
-- DIAGNOSTICS — run these during a load test
-- ─────────────────────────────────────────────────────────────────────────────

-- 1. Top slow queries (requires performance_schema):
SELECT digest_text, COUNT_STAR, AVG_TIMER_WAIT/1e9 AS avg_ms,
       MAX_TIMER_WAIT/1e9 AS max_ms, SUM_ROWS_EXAMINED
FROM performance_schema.events_statements_summary_by_digest
ORDER BY AVG_TIMER_WAIT DESC LIMIT 10;

-- 2. Current InnoDB lock waits:
SELECT r.trx_id waiting_trx, r.trx_mysql_thread_id waiting_thread,
       r.trx_query waiting_query,
       b.trx_id blocking_trx, b.trx_mysql_thread_id blocking_thread
FROM information_schema.innodb_lock_waits w
JOIN information_schema.innodb_trx b ON b.trx_id = w.blocking_trx_id
JOIN information_schema.innodb_trx r ON r.trx_id = w.requesting_trx_id;

-- 3. Buffer pool hit ratio (should be >99%):
SELECT (1 - (Innodb_buffer_pool_reads / Innodb_buffer_pool_read_requests)) * 100
       AS hit_ratio_pct
FROM (
  SELECT VARIABLE_VALUE AS Innodb_buffer_pool_reads
  FROM performance_schema.global_status
  WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads'
) r,
(
  SELECT VARIABLE_VALUE AS Innodb_buffer_pool_read_requests
  FROM performance_schema.global_status
  WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests'
) rr;

-- 4. Replication lag (run on replica):
-- SHOW REPLICA STATUS\G
