package com.keranzhu.shop.order;

import com.keranzhu.shop.common.InsufficientStockException;
import com.keranzhu.shop.common.NotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Entry point for checkout. Wraps {@link CheckoutService} so that a request racing
 * another request with the same idempotency key returns the order the winner created
 * instead of an error.
 */
@Service
public class OrderService {

    private final CheckoutService checkout;
    private final OrderRepository orders;
    private final MeterRegistry metrics;

    public OrderService(CheckoutService checkout, OrderRepository orders, MeterRegistry metrics) {
        this.checkout = checkout;
        this.orders = orders;
        this.metrics = metrics;
    }

    public OrderView checkout(Long userId, String idempotencyKey) {
        validateKey(idempotencyKey);
        try {
            Order order = checkout.placeOrder(userId, idempotencyKey);
            count("success");
            return OrderView.of(order);
        } catch (DataIntegrityViolationException duplicate) {
            // Lost the race on uk_orders_user_idem: our transaction rolled back (stock included),
            // and the other request's order is the one the client should see.
            count("duplicate");
            return checkout.findExisting(userId, idempotencyKey)
                    .map(OrderView::of)
                    .orElseThrow(() -> duplicate);
        } catch (InsufficientStockException e) {
            // A retry of a request that already succeeded may see the stock it took itself.
            var existing = checkout.findExisting(userId, idempotencyKey);
            if (existing.isPresent()) {
                count("duplicate");
                return OrderView.of(existing.get());
            }
            count("out_of_stock");
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public List<OrderView> list(Long userId) {
        return orders.findByUserIdOrderByIdDesc(userId).stream().map(OrderView::of).toList();
    }

    @Transactional(readOnly = true)
    public OrderView get(Long userId, Long orderId) {
        return orders.findByIdAndUserId(orderId, userId)
                .map(OrderView::of)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new IllegalArgumentException("Idempotency-Key must be 1-64 characters");
        }
    }

    private void count(String outcome) {
        metrics.counter("shop.checkout", "outcome", outcome).increment();
    }
}
