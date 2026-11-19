package com.keranzhu.shop.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, Long> {

    boolean existsBySku(String sku);

    /**
     * Conditional, atomic stock decrement. MySQL evaluates the WHERE clause under a row lock,
     * so two concurrent checkouts can never both take the last unit.
     *
     * @return 1 if stock was reserved, 0 if there was not enough
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE products SET stock = stock - :qty WHERE id = :id AND stock >= :qty",
            nativeQuery = true)
    int decrementStockIfAvailable(@Param("id") long id, @Param("qty") int qty);
}
