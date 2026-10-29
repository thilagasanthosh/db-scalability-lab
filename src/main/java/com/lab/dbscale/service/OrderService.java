package com.lab.dbscale.service;

import com.lab.dbscale.dto.OrderPageResponse;
import com.lab.dbscale.dto.OrderPageResponse.OrderSummary;
import com.lab.dbscale.model.Order;
import com.lab.dbscale.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepo;

    // ── Lab #1 + #3 (BAD): No index, OFFSET pagination ──────────────────────
    @Transactional(readOnly = true)
    public OrderPageResponse getOrdersOffsetBad(Long customerId, String status,
                                                 int page, int size) {
        var pageable = PageRequest.of(page, size);
        List<Order> orders = orderRepo.findByCustomerIdAndStatusOrderByCreatedAtDesc(
                customerId, status, pageable);
        return toPageResponse(orders, null);
    }

    // ── Lab #3 (GOOD): Keyset pagination ─────────────────────────────────────
    @Transactional(readOnly = true)  // routes to replica if profile=replica
    public OrderPageResponse getOrdersKeyset(Long customerId, Long lastId, int size) {
        var pageable = PageRequest.of(0, size + 1);
        List<Order> orders = lastId == null
                ? orderRepo.findByCustomerIdOffset(customerId, pageable)
                : orderRepo.findByCustomerIdKeyset(customerId, lastId, pageable);

        boolean hasMore = orders.size() > size;
        if (hasMore) orders = orders.subList(0, size);
        Long nextCursor = hasMore ? orders.get(orders.size() - 1).getId() : null;
        return toPageResponse(orders, nextCursor);
    }

    // ── Lab #2 (BAD): N+1 — items lazily loaded ───────────────────────────────
    @Transactional(readOnly = true)
    public OrderPageResponse getOrdersWithItemsN1(Long customerId, String status) {
        List<Order> orders = orderRepo.findOrdersWithoutItems(customerId, status);
        // Accessing .getItems() triggers a separate SELECT per order → N+1
        return toPageResponseWithItems(orders);
    }

    // ── Lab #2 + #6 (GOOD): JOIN FETCH + cache ────────────────────────────────
    @Transactional(readOnly = true)
    @Cacheable(value = "orders", key = "#customerId + ':' + #status")
    public OrderPageResponse getOrdersWithItemsJoinFetch(Long customerId, String status) {
        List<Order> orders = orderRepo.findOrdersWithItems(customerId, status);
        return toPageResponseWithItems(orders);
    }

    // ── Lab #11 pattern (documented, not wired to Kafka here): ──────────────
    // In a real async setup you'd publish an event here and a @KafkaListener
    // consumer would persist the order. The endpoint returns 202 immediately.
    @CacheEvict(value = "orders", allEntries = true)
    public String queueOrder(Long customerId, String orderJson) {
        log.info("[async-pattern] Order event for customer {} — would publish to Kafka", customerId);
        // Simulate the fast response of an async write:
        // real implementation: kafkaTemplate.send("orders.created", customerId.toString(), orderJson)
        return "Order accepted (async pattern — see Lab #11 in the guide)";
    }

    // ── Helpers ────────────────────────────────────────────────────────────────
    private OrderPageResponse toPageResponse(List<Order> orders, Long nextCursor) {
        List<OrderSummary> summaries = orders.stream()
                .map(o -> new OrderSummary(
                        o.getId(), o.getStatus(), o.getTotalAmount(),
                        o.getCreatedAt(), List.of()))
                .toList();
        return new OrderPageResponse(summaries, nextCursor, nextCursor != null);
    }

    private OrderPageResponse toPageResponseWithItems(List<Order> orders) {
        List<OrderSummary> summaries = orders.stream()
                .map(o -> new OrderSummary(
                        o.getId(), o.getStatus(), o.getTotalAmount(),
                        o.getCreatedAt(),
                        o.getItems() == null ? List.of()
                                : o.getItems().stream()
                                             .map(i -> i.getProductName())
                                             .toList()))
                .toList();
        return new OrderPageResponse(summaries, null, false);
    }
}
