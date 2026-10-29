-- =============================================================================
-- LAB SCHEMA
-- Run this ONCE against your local MySQL
-- =============================================================================

CREATE DATABASE IF NOT EXISTS scalabilitylab
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE scalabilitylab;

-- ── customers ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS customers (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  name       VARCHAR(100) NOT NULL,
  email      VARCHAR(150) NOT NULL,
  created_at DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uq_customer_email (email)
) ENGINE=InnoDB;

-- ── products (Lab #8: hot-row contention) ────────────────────────────────────
CREATE TABLE IF NOT EXISTS products (
  id             BIGINT         NOT NULL AUTO_INCREMENT,
  name           VARCHAR(200)   NOT NULL,
  price          DECIMAL(10,2)  NOT NULL,
  stock_quantity INT            NOT NULL DEFAULT 10000,
  version        BIGINT                  DEFAULT 0,
  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- ── orders ───────────────────────────────────────────────────────────────────
-- NOTE: start WITHOUT the composite index (Lab #1 baseline).
-- After measuring, add the index and compare p95.
CREATE TABLE IF NOT EXISTS orders (
  id           BIGINT         NOT NULL AUTO_INCREMENT,
  customer_id  BIGINT         NOT NULL,
  status       VARCHAR(20)    NOT NULL,
  total_amount DECIMAL(12,2)  NOT NULL,
  created_at   DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  version      BIGINT                  DEFAULT 0,
  PRIMARY KEY (id),
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id)
    REFERENCES customers (id) ON DELETE CASCADE
  -- Lab #1 FIX (run after baseline):
  -- ,INDEX idx_orders_customer_status_created (customer_id, status, created_at)
) ENGINE=InnoDB;

-- ── order_items ──────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS order_items (
  id           BIGINT         NOT NULL AUTO_INCREMENT,
  order_id     BIGINT         NOT NULL,
  product_name VARCHAR(200)   NOT NULL,
  quantity     INT            NOT NULL,
  unit_price   DECIMAL(10,2)  NOT NULL,
  PRIMARY KEY (id),
  INDEX idx_order_items_order_id (order_id),
  CONSTRAINT fk_items_order FOREIGN KEY (order_id)
    REFERENCES orders (id) ON DELETE CASCADE
) ENGINE=InnoDB;

-- ── MySQL config tweaks (run as root) ─────────────────────────────────────────
-- SET GLOBAL slow_query_log = 'ON';
-- SET GLOBAL long_query_time = 0.2;
-- SET GLOBAL log_output = 'TABLE';   -- query to performance_schema.events_statements_history
