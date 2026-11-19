package com.keranzhu.shop.order;

import com.keranzhu.shop.cart.CartItem;
import com.keranzhu.shop.cart.CartItemRepository;
import com.keranzhu.shop.catalog.Product;
import com.keranzhu.shop.catalog.ProductCacheEvictor;
import com.keranzhu.shop.catalog.ProductRepository;
import com.keranzhu.shop.common.InsufficientStockException;
import com.keranzhu.shop.messaging.MessagingConfig;
import com.keranzhu.shop.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Places an order in one MySQL transaction:
 * <ol>
 *   <li>return the existing order if this idempotency key was already used,</li>
 *   <li>reserve stock with conditional UPDATEs (rolls back everything if any item is short),</li>
 *   <li>insert the order (unique (user_id, idempotency_key) blocks concurrent duplicates),</li>
 *   <li>append an OrderCreated event to the outbox and clear the cart,</li>
 *   <li>evict the touched products from Redis after commit.</li>
 * </ol>
 * Stock is always validated against MySQL, never against the Redis cache.
 */
@Service
public class CheckoutService {

    private final OrderRepository orders;
    private final CartItemRepository cart;
    private final ProductRepository products;
    private final OutboxWriter outbox;
    private final ProductCacheEvictor cacheEvictor;

    public CheckoutService(OrderRepository orders, CartItemRepository cart, ProductRepository products,
                           OutboxWriter outbox, ProductCacheEvictor cacheEvictor) {
        this.orders = orders;
        this.cart = cart;
        this.products = products;
        this.outbox = outbox;
        this.cacheEvictor = cacheEvictor;
    }

    @Transactional(readOnly = true)
    public Optional<Order> findExisting(Long userId, String idempotencyKey) {
        return orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
    }

    @Transactional
    public Order placeOrder(Long userId, String idempotencyKey) {
        Optional<Order> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }

        // Sorted by product id so concurrent checkouts lock rows in the same order (no deadlocks).
        List<CartItem> items = cart.findByUserIdOrderByProductId(userId);
        if (items.isEmpty()) {
            throw new IllegalArgumentException("Cart is empty");
        }

        for (CartItem item : items) {
            if (products.decrementStockIfAvailable(item.getProductId(), item.getQuantity()) == 0) {
                throw new InsufficientStockException(item.getProductId());
            }
        }

        // Prices come from MySQL inside this transaction, not from the cache.
        Map<Long, Product> byId = products.findAllById(items.stream().map(CartItem::getProductId).toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        Order order = new Order(userId, idempotencyKey);
        for (CartItem item : items) {
            order.addItem(item.getProductId(), item.getQuantity(), byId.get(item.getProductId()).getPrice());
        }
        orders.saveAndFlush(order); // flush now so a duplicate key fails here, before the outbox write

        String eventId = UUID.randomUUID().toString();
        outbox.append(eventId, order.getId(), MessagingConfig.ORDER_CREATED_ROUTING_KEY,
                new OrderCreatedEvent(eventId, order.getId(), userId, order.getTotal(),
                        items.stream().map(i -> new OrderCreatedEvent.Item(i.getProductId(), i.getQuantity())).toList()));

        cart.deleteAllForUser(userId);
        cacheEvictor.evictAfterCommit(byId.keySet());
        return order;
    }
}
