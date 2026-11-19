package com.keranzhu.shop.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderView(Long id, OrderStatus status, BigDecimal total, Instant createdAt, List<Line> items) {

    public record Line(Long productId, int quantity, BigDecimal unitPrice) {
    }

    public static OrderView of(Order o) {
        return new OrderView(o.getId(), o.getStatus(), o.getTotal(), o.getCreatedAt(),
                o.getItems().stream()
                        .map(i -> new Line(i.getProductId(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }
}
