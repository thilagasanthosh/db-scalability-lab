package com.lab.dbscale.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

/**
 * Used for Lab #8: Hot-row contention (stock decrement).
 * Multiple threads try to decrement stock concurrently.
 */
@Entity
@Table(name = "products")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    private Integer stockQuantity;

    @Version
    private Long version; // Optimistic locking
}
