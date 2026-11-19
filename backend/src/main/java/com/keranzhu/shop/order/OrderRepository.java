package com.keranzhu.shop.order;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    @EntityGraph(attributePaths = "items")
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    @EntityGraph(attributePaths = "items")
    List<Order> findByUserIdOrderByIdDesc(Long userId);

    /** Moves PENDING -> CONFIRMED; a no-op if already confirmed. */
    @Modifying
    @Query(value = "UPDATE orders SET status = 'CONFIRMED' WHERE id = :orderId AND status = 'PENDING'",
            nativeQuery = true)
    int confirm(Long orderId);
}
