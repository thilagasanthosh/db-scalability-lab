-- =============================================================================
-- SEED DATA — generates 5M orders across 100K customers
-- Estimated runtime: ~5 minutes on a laptop (done once)
-- =============================================================================

USE scalabilitylab;

-- ── 1. Customers (100,000) ────────────────────────────────────────────────────
INSERT INTO customers (name, email, created_at)
WITH RECURSIVE seq AS (
  SELECT 1 AS n
  UNION ALL
  SELECT n + 1 FROM seq WHERE n < 100000
)
SELECT
  CONCAT('Customer ', n)                       AS name,
  CONCAT('user', n, '@example.com')            AS email,
  DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*730) DAY) AS created_at
FROM seq;

-- ── 2. Products (50) ──────────────────────────────────────────────────────────
INSERT INTO products (name, price, stock_quantity, version) VALUES
  ('Laptop Pro 15',    1299.99, 100, 0),
  ('Wireless Mouse',     29.99, 500, 0),
  ('Mechanical Keyboard',89.99, 300, 0),
  ('USB-C Hub',          49.99, 400, 0),
  ('4K Monitor',        449.99,  50, 0);

-- ── 3. Orders (5,000,000) — batch via stored procedure ───────────────────────
DELIMITER $$

DROP PROCEDURE IF EXISTS seed_orders$$
CREATE PROCEDURE seed_orders()
BEGIN
  DECLARE batch_size INT DEFAULT 10000;
  DECLARE total      INT DEFAULT 5000000;
  DECLARE inserted   INT DEFAULT 0;

  WHILE inserted < total DO
    INSERT INTO orders (customer_id, status, total_amount, created_at)
    WITH RECURSIVE seq AS (
      SELECT 1 AS n
      UNION ALL
      SELECT n + 1 FROM seq WHERE n < batch_size
    )
    SELECT
      FLOOR(RAND() * 100000) + 1                    AS customer_id,
      ELT(FLOOR(RAND()*4)+1,
          'PENDING','SHIPPED','DELIVERED','CANCELLED') AS status,
      ROUND(RAND() * 500 + 10, 2)                   AS total_amount,
      DATE_SUB(NOW(), INTERVAL FLOOR(RAND()*365) DAY
                    + INTERVAL FLOOR(RAND()*86400) SECOND) AS created_at
    FROM seq;

    SET inserted = inserted + batch_size;
    SELECT CONCAT('Inserted: ', inserted, ' / ', total) AS progress;
  END WHILE;
END$$

DELIMITER ;

CALL seed_orders();
DROP PROCEDURE seed_orders;

-- ── 4. Order items (one per order, 5M rows) ───────────────────────────────────
INSERT INTO order_items (order_id, product_name, quantity, unit_price)
SELECT
  id,
  ELT(FLOOR(RAND()*5)+1,
      'Laptop Pro 15','Wireless Mouse','Mechanical Keyboard','USB-C Hub','4K Monitor'),
  FLOOR(RAND()*3)+1,
  ROUND(RAND()*200+10, 2)
FROM orders;

-- ── 5. Verify ─────────────────────────────────────────────────────────────────
SELECT 'customers' AS tbl, COUNT(*) FROM customers
UNION ALL
SELECT 'orders',           COUNT(*) FROM orders
UNION ALL
SELECT 'order_items',      COUNT(*) FROM order_items
UNION ALL
SELECT 'products',         COUNT(*) FROM products;
