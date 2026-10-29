package com.lab.dbscale.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/lab")
@RequiredArgsConstructor
@Tag(name = "Lab Utilities", description = "Seed status and sample data — used by the embedded guide at /")
public class LabController {

    private final JdbcTemplate jdbc;

    @Operation(
        summary = "Seed status + index check",
        description = """
            Returns row counts for all seeded tables and whether the Lab #1
            composite index (`idx_orders_customer_status_created`) has been applied.

            The embedded lab guide calls this on page load to populate the status bar.
            """
    )
    @GetMapping("/status")
    public Map<String, Object> status() {
        Long customers   = jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class);
        Long orders      = jdbc.queryForObject("SELECT COUNT(*) FROM orders", Long.class);
        Long orderItems  = jdbc.queryForObject("SELECT COUNT(*) FROM order_items", Long.class);
        Long products    = jdbc.queryForObject("SELECT COUNT(*) FROM products", Long.class);

        Integer indexExists = jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.STATISTICS " +
            "WHERE TABLE_SCHEMA = DATABASE() " +
            "AND TABLE_NAME = 'orders' " +
            "AND INDEX_NAME = 'idx_orders_customer_status_created'",
            Integer.class);

        return Map.of(
            "seeded",         orders != null && orders > 0,
            "counts",         Map.of(
                "customers",  customers  != null ? customers  : 0,
                "orders",     orders     != null ? orders     : 0,
                "orderItems", orderItems != null ? orderItems : 0,
                "products",   products   != null ? products   : 0
            ),
            "indexLab1Applied", indexExists != null && indexExists > 0
        );
    }

    @Operation(
        summary = "Get a sample customer ID",
        description = """
            Returns the `customerId` of the customer with the most orders in the seed data.
            The embedded lab guide uses this to pre-populate customer IDs in live test buttons,
            so you always test against a customer that has real data.
            """
    )
    @GetMapping("/sample-customer")
    public Map<String, Object> sampleCustomer() {
        Long id = jdbc.queryForObject(
            "SELECT customer_id FROM orders GROUP BY customer_id " +
            "ORDER BY COUNT(*) DESC LIMIT 1", Long.class);
        return Map.of("customerId", id != null ? id : 1L);
    }
}
