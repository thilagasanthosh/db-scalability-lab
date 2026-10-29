package com.lab.dbscale.init;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seeds the database on first startup.
 *
 * Counts:
 *   500  customers
 *   50,000 orders  (enough for indexes to matter without taking forever)
 *   ≈ 50,000 order items (one per order)
 *   5    products
 *
 * Idempotent: skips if data already exists (checks customers count).
 * Uses plain JDBC batch inserts instead of JPA so AUTO_INCREMENT doesn't
 * disable batching (Hibernate IDENTITY generation disables batch mode).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    private static final int CUSTOMER_COUNT = 500;
    private static final int ORDER_COUNT    = 50_000;
    private static final int BATCH_SIZE     = 500;

    private static final String[] STATUSES  = {"PENDING", "SHIPPED", "DELIVERED", "CANCELLED"};
    private static final String[] PRODUCTS  = {
        "Laptop Pro 15", "Wireless Mouse", "Mechanical Keyboard", "USB-C Hub", "4K Monitor"
    };

    @Override
    public void run(ApplicationArguments args) {
        Long existing = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
        if (existing != null && existing > 0) {
            log.info("[DataInitializer] Seed already exists ({} customers). Skipping.", existing);
            return;
        }

        log.info("[DataInitializer] Seeding {} customers and {} orders...", CUSTOMER_COUNT, ORDER_COUNT);
        long start = System.currentTimeMillis();

        seedCustomers();
        seedProducts();
        seedOrders();

        log.info("[DataInitializer] Seed complete in {} ms", System.currentTimeMillis() - start);
        log.info("[DataInitializer] Lab ready → http://localhost:8080");
    }

    // ── Customers ──────────────────────────────────────────────────────────
    private void seedCustomers() {
        List<Object[]> batch = new ArrayList<>(BATCH_SIZE);
        for (int i = 1; i <= CUSTOMER_COUNT; i++) {
            batch.add(new Object[]{
                "Customer " + i,
                "user" + i + "@example.com",
                randomPastTimestamp(730)
            });
            if (batch.size() == BATCH_SIZE) {
                jdbc.batchUpdate(
                    "INSERT INTO customers (name, email, created_at) VALUES (?, ?, ?)", batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO customers (name, email, created_at) VALUES (?, ?, ?)", batch);
        }
        log.info("[DataInitializer] {} customers inserted", CUSTOMER_COUNT);
    }

    // ── Products ───────────────────────────────────────────────────────────
    private void seedProducts() {
        double[] prices = {1299.99, 29.99, 89.99, 49.99, 449.99};
        int[] stocks    = {100, 500, 300, 400, 50};
        for (int i = 0; i < PRODUCTS.length; i++) {
            jdbc.update(
                "INSERT IGNORE INTO products (name, price, stock_quantity, version) VALUES (?, ?, ?, 0)",
                PRODUCTS[i], prices[i], stocks[i]);
        }
        log.info("[DataInitializer] {} products inserted", PRODUCTS.length);
    }

    // ── Orders + items ─────────────────────────────────────────────────────
    private void seedOrders() {
        Random rng = new Random(42);

        List<Object[]> orderBatch = new ArrayList<>(BATCH_SIZE);
        List<Object[]> itemBatch  = new ArrayList<>(BATCH_SIZE);

        // We need order IDs for the items — fetch the starting ID first
        Long maxIdBefore = jdbc.queryForObject(
            "SELECT COALESCE(MAX(id), 0) FROM orders", Long.class);
        long nextId = (maxIdBefore == null ? 0 : maxIdBefore) + 1;

        for (int i = 0; i < ORDER_COUNT; i++) {
            long customerId  = (long)(rng.nextInt(CUSTOMER_COUNT) + 1);
            String status    = STATUSES[rng.nextInt(STATUSES.length)];
            double amount    = Math.round((rng.nextDouble() * 490 + 10) * 100.0) / 100.0;

            orderBatch.add(new Object[]{customerId, status, amount, randomPastTimestamp(365)});

            // We'll link items after inserting orders by refetching IDs in blocks
            if (orderBatch.size() == BATCH_SIZE) {
                jdbc.batchUpdate(
                    "INSERT INTO orders (customer_id, status, total_amount, created_at, version) VALUES (?, ?, ?, ?, 0)",
                    orderBatch);
                insertItemsForBlock(nextId, orderBatch.size(), rng, itemBatch);
                nextId += orderBatch.size();
                orderBatch.clear();
                itemBatch.clear();
            }
        }
        // remainder
        if (!orderBatch.isEmpty()) {
            jdbc.batchUpdate(
                "INSERT INTO orders (customer_id, status, total_amount, created_at, version) VALUES (?, ?, ?, ?, 0)",
                orderBatch);
            insertItemsForBlock(nextId, orderBatch.size(), rng, itemBatch);
            orderBatch.clear();
            itemBatch.clear();
        }

        log.info("[DataInitializer] {} orders + order_items inserted", ORDER_COUNT);
    }

    private void insertItemsForBlock(long startId, int count,
                                      Random rng, List<Object[]> itemBatch) {
        for (int k = 0; k < count; k++) {
            String product = PRODUCTS[rng.nextInt(PRODUCTS.length)];
            int qty        = rng.nextInt(3) + 1;
            double price   = Math.round((rng.nextDouble() * 190 + 10) * 100.0) / 100.0;
            itemBatch.add(new Object[]{startId + k, product, qty, price});
        }
        jdbc.batchUpdate(
            "INSERT INTO order_items (order_id, product_name, quantity, unit_price) VALUES (?, ?, ?, ?)",
            itemBatch);
        itemBatch.clear();
    }

    // ── Helper: random past Timestamp ─────────────────────────────────────────
    private static final Random RNG_TS = new Random(99);

    private Timestamp randomPastTimestamp(int maxDaysAgo) {
        long secondsAgo = (long)(RNG_TS.nextDouble() * maxDaysAgo * 86400L);
        return Timestamp.valueOf(LocalDateTime.now().minusSeconds(secondsAgo));
    }
}
