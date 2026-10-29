package com.lab.dbscale.repository;

import com.lab.dbscale.model.Order;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

    // ── Lab #1 (BAD): Full scan — no index on (customer_id, status, created_at) ──
    List<Order> findByCustomerIdAndStatusOrderByCreatedAtDesc(
            Long customerId, String status, Pageable pageable);

    // ── Lab #3 (BAD): OFFSET pagination — slow on deep pages ──
    @Query("SELECT o FROM Order o WHERE o.customer.id = :customerId " +
           "ORDER BY o.id DESC")
    List<Order> findByCustomerIdOffset(@Param("customerId") Long customerId, Pageable pageable);

    // ── Lab #3 (GOOD): Keyset pagination — WHERE id < :lastId ──
    @Query("SELECT o FROM Order o WHERE o.customer.id = :customerId " +
           "AND o.id < :lastId ORDER BY o.id DESC")
    List<Order> findByCustomerIdKeyset(
            @Param("customerId") Long customerId,
            @Param("lastId") Long lastId,
            Pageable pageable);

    // ── Lab #2 (BAD): N+1 — items loaded lazily per order ──
    @Query("SELECT o FROM Order o WHERE o.customer.id = :customerId AND o.status = :status")
    List<Order> findOrdersWithoutItems(
            @Param("customerId") Long customerId,
            @Param("status") String status);

    // ── Lab #2 (GOOD): JOIN FETCH avoids N+1 ──
    @Query("SELECT DISTINCT o FROM Order o " +
           "JOIN FETCH o.customer c " +
           "LEFT JOIN FETCH o.items i " +
           "WHERE c.id = :customerId AND o.status = :status " +
           "ORDER BY o.createdAt DESC")
    List<Order> findOrdersWithItems(
            @Param("customerId") Long customerId,
            @Param("status") String status);

    long countByCustomerIdAndStatus(Long customerId, String status);
}
