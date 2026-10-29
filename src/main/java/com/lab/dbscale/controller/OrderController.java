package com.lab.dbscale.controller;

import com.lab.dbscale.dto.OrderPageResponse;
import com.lab.dbscale.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order endpoints — Labs #1 #2 #3 #6 #11")
public class OrderController {

    private final OrderService orderService;

    // ── Lab #1 + #3 (BAD): full-scan + OFFSET ────────────────────────────────
    @Operation(
        summary = "LAB #1 BAD + LAB #3 BAD — Full scan + OFFSET pagination",
        description = """
            **Problems demonstrated:**
            - No composite index → `EXPLAIN type=ALL`, full table scan
            - OFFSET pagination → scans and discards N rows at deep pages

            **How to test:**
            1. Call this endpoint and note the response time
            2. Run `EXPLAIN SELECT * FROM orders WHERE customer_id=? AND status=? ORDER BY created_at DESC`
            3. Apply the fix from `sql/03_lab_fixes.sql`
            4. Compare with `/api/customers/{id}/orders` (the fixed endpoint)

            **k6:** `k6 run k6-scripts/lab01_index.js --env ENDPOINT=bad`
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Orders returned (slow — no index, OFFSET scan)")
    })
    @GetMapping("/customers/{customerId}/orders/bad")
    public ResponseEntity<OrderPageResponse> getOrdersBad(
            @Parameter(description = "Customer ID (use /api/lab/sample-customer for a busy one)", example = "1")
            @PathVariable Long customerId,
            @Parameter(description = "Order status filter", example = "SHIPPED")
            @RequestParam(defaultValue = "SHIPPED") String status,
            @Parameter(description = "Page number — try page=500 to exaggerate OFFSET cost", example = "0")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                orderService.getOrdersOffsetBad(customerId, status, page, size));
    }

    // ── Lab #3 (GOOD): keyset pagination ─────────────────────────────────────
    @Operation(
        summary = "LAB #3 GOOD — Keyset (cursor) pagination",
        description = """
            **Fix for:** OFFSET pagination (Lab #3 BAD)

            Uses `WHERE id < :lastId ORDER BY id DESC` — constant B-tree seek on any page depth.
            Deep pages stay as fast as page 1.

            **Follow the cursor:** use `nextCursor` from the response as `lastId` on the next call.

            **k6:** `k6 run k6-scripts/lab03_pagination.js --env MODE=keyset`
            """
    )
    @GetMapping("/customers/{customerId}/orders/keyset")
    public ResponseEntity<OrderPageResponse> getOrdersKeyset(
            @Parameter(description = "Customer ID", example = "1")
            @PathVariable Long customerId,
            @Parameter(description = "Last seen order ID (omit for first page)", example = "50000")
            @RequestParam(required = false) Long lastId,
            @Parameter(description = "Page size", example = "20")
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                orderService.getOrdersKeyset(customerId, lastId, size));
    }

    // ── Lab #2 (BAD): N+1 queries ────────────────────────────────────────────
    @Operation(
        summary = "LAB #2 BAD — N+1 query problem",
        description = """
            **Problem:** Lazy loading fires one SQL query per order to fetch its items.
            20 orders = 21 total SQL queries.

            **How to see it:** Enable `logging.level.org.hibernate.SQL=DEBUG` in application.yml,
            then call this endpoint and count the SELECT statements in the log.

            **k6:** `k6 run k6-scripts/lab02_n1.js --env ENDPOINT=n1`
            """
    )
    @GetMapping("/customers/{customerId}/orders/n1")
    public ResponseEntity<OrderPageResponse> getOrdersN1(
            @Parameter(description = "Customer ID", example = "1")
            @PathVariable Long customerId,
            @Parameter(description = "Order status filter", example = "SHIPPED")
            @RequestParam(defaultValue = "SHIPPED") String status) {
        return ResponseEntity.ok(
                orderService.getOrdersWithItemsN1(customerId, status));
    }

    // ── Lab #2 + #6 (GOOD): JOIN FETCH + Redis cache ─────────────────────────
    @Operation(
        summary = "LAB #2 GOOD + LAB #6 — JOIN FETCH + Redis @Cacheable",
        description = """
            **Fixes two problems:**
            1. **N+1 (Lab #2):** Single `LEFT JOIN FETCH` query instead of N+1 lazy loads
            2. **Cache (Lab #6):** `@Cacheable("orders")` — second call returns from Redis in ~2ms

            **How to compare with cache:**
            - Call once (cold) — goes to DB
            - Call again — Redis hit, ~2ms response

            **Requires Redis profile for caching:**
            ```
            mvn spring-boot:run -Dspring-boot.run.profiles=redis
            ```

            **k6:** `k6 run k6-scripts/lab06_cache.js`
            """
    )
    @GetMapping("/customers/{customerId}/orders")
    public ResponseEntity<OrderPageResponse> getOrders(
            @Parameter(description = "Customer ID", example = "1")
            @PathVariable Long customerId,
            @Parameter(description = "Order status filter", example = "SHIPPED")
            @RequestParam(defaultValue = "SHIPPED") String status) {
        return ResponseEntity.ok(
                orderService.getOrdersWithItemsJoinFetch(customerId, status));
    }

    // ── Lab #11: Async write pattern ─────────────────────────────────────────
    @Operation(
        summary = "LAB #11 — Async write (202 Accepted immediately)",
        description = """
            **Pattern:** Returns 202 Accepted in <5ms instead of waiting for DB write.

            In a production setup this publishes to a Kafka topic via the outbox pattern.
            A `@KafkaListener` consumer then persists the event at its own pace.

            **Trade-off:** The new order is NOT immediately queryable.

            **This demo:** logs the event only (Kafka dependency not wired).
            To wire real Kafka: add `spring-kafka` to pom.xml and add a `@KafkaListener`.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "202", description = "Order event accepted and queued")
    })
    @PostMapping("/customers/{customerId}/orders/async")
    public ResponseEntity<String> placeOrderAsync(
            @Parameter(description = "Customer ID", example = "1")
            @PathVariable Long customerId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Order payload (optional JSON body)",
                required = false
            )
            @RequestBody(required = false) String orderJson) {
        String msg = orderService.queueOrder(customerId, orderJson != null ? orderJson : "{}");
        return ResponseEntity.accepted().body(msg);
    }
}
