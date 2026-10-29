package com.lab.dbscale.service;

import com.lab.dbscale.model.Product;
import com.lab.dbscale.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepo;

    @Cacheable(value = "products", key = "#id")
    @Transactional(readOnly = true)
    public Product getProduct(Long id) {
        return productRepo.findById(id)
                .orElseThrow(() -> new RuntimeException("Product not found: " + id));
    }

    /**
     * Lab #8 (BAD): Optimistic locking — causes StaleObjectStateException under
     * high concurrency, which we catch and translate to a 409.
     */
    @Transactional
    public boolean decrementStockOptimistic(Long productId, int qty) {
        try {
            Product p = productRepo.findById(productId)
                    .orElseThrow(() -> new RuntimeException("Product not found"));
            if (p.getStockQuantity() < qty) return false;
            p.setStockQuantity(p.getStockQuantity() - qty);
            productRepo.save(p); // @Version kicks in here
            return true;
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Optimistic lock conflict for product {}", productId);
            return false; // caller should retry
        }
    }

    /**
     * Lab #8 (GOOD): Atomic conditional UPDATE — no lock contention,
     * returns false instead of throwing when stock is 0.
     */
    @Transactional
    public boolean decrementStockAtomic(Long productId, int qty) {
        int updated = productRepo.decrementStock(productId, qty);
        return updated == 1;
    }
}
