package com.lab.dbscale.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OrderPageResponse(
        List<OrderSummary> orders,
        Long nextCursor,   // for keyset pagination
        boolean hasMore
) {
    public record OrderSummary(
            Long id,
            String status,
            BigDecimal totalAmount,
            LocalDateTime createdAt,
            List<String> itemNames  // populated only by JOIN FETCH endpoint
    ) {}
}
