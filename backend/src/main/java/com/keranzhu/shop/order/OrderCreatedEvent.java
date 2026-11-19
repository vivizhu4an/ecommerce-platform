package com.keranzhu.shop.order;

import java.math.BigDecimal;
import java.util.List;

/** Payload of the "order.created" message. {@code eventId} is the de-duplication key. */
public record OrderCreatedEvent(String eventId, Long orderId, Long userId, BigDecimal total, List<Item> items) {

    public record Item(Long productId, int quantity) {
    }
}
