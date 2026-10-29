package com.lab.dbscale.controller;

import com.lab.dbscale.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product/stock endpoints — Lab #8 hot-row contention")
public class ProductController {

    private final ProductService productService;

    @Operation(
        summary = "Get product by ID",
        description = "Returns product details including current stock quantity."
    )
    @GetMapping("/{id}")
    public ResponseEntity<?> getProduct(
            @Parameter(description = "Product ID (1–5 seeded)", example = "1")
            @PathVariable Long id) {
        return ResponseEntity.ok(productService.getProduct(id));
    }

    @Operation(
        summary = "LAB #8 BAD — Optimistic lock (high 409 conflict rate)",
        description = """
            **Problem:** Read → check → write is a race condition under concurrency.
            `@Version` throws `OptimisticLockException` when two threads modify the same row.

            **How to see it:**
            Run k6 with 200 VUs — you'll see ~40% of requests return HTTP 409.

            **Reset stock before each run:**
            ```sql
            UPDATE products SET stock_quantity = 100000, version = 0 WHERE id = 1;
            ```

            **k6:** `k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=bad`
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stock decremented successfully"),
        @ApiResponse(responseCode = "409", description = "Optimistic lock conflict — retry required")
    })
    @PostMapping("/{id}/decrement/bad")
    public ResponseEntity<String> decrementBad(
            @Parameter(description = "Product ID", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Quantity to decrement", example = "1")
            @RequestParam(defaultValue = "1") int qty) {
        boolean ok = productService.decrementStockOptimistic(id, qty);
        return ok ? ResponseEntity.ok("decremented")
                  : ResponseEntity.status(409).body("conflict or insufficient stock");
    }

    @Operation(
        summary = "LAB #8 GOOD — Atomic UPDATE (zero conflicts)",
        description = """
            **Fix:** Single `UPDATE products SET stock = stock - qty WHERE id = ? AND stock >= qty`
            — one serializable operation, no read-modify-write race.

            Returns 422 only when stock is genuinely insufficient.
            With 200 VUs: 0% 409, clean throughput.

            **Reset stock before each run:**
            ```sql
            UPDATE products SET stock_quantity = 100000 WHERE id = 1;
            ```

            **k6:** `k6 run k6-scripts/lab08_stock_contention.js --env STRATEGY=atomic`
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Stock decremented atomically"),
        @ApiResponse(responseCode = "422", description = "Insufficient stock (no conflict — genuine OOS)")
    })
    @PostMapping("/{id}/decrement/atomic")
    public ResponseEntity<String> decrementAtomic(
            @Parameter(description = "Product ID", example = "1")
            @PathVariable Long id,
            @Parameter(description = "Quantity to decrement", example = "1")
            @RequestParam(defaultValue = "1") int qty) {
        boolean ok = productService.decrementStockAtomic(id, qty);
        return ok ? ResponseEntity.ok("decremented")
                  : ResponseEntity.status(422).body("insufficient stock");
    }
}
