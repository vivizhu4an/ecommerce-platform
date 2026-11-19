package com.keranzhu.shop.cart;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    List<CartItem> findByUserIdOrderByProductId(Long userId);

    Optional<CartItem> findByUserIdAndProductId(Long userId, Long productId);

    @Modifying
    @Query("delete from CartItem c where c.userId = :userId")
    void deleteAllForUser(Long userId);

    @Modifying
    @Query("delete from CartItem c where c.userId = :userId and c.productId = :productId")
    void deleteItem(Long userId, Long productId);
}
