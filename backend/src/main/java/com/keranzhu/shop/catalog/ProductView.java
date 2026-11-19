package com.keranzhu.shop.catalog;

import java.math.BigDecimal;

/**
 * Read model returned by the API and stored in Redis.
 * The {@code stock} value here may be slightly stale when served from cache;
 * checkout never trusts it and re-checks stock in MySQL.
 */
public record ProductView(Long id, String sku, String name, String description,
                          BigDecimal price, int stock) {
}
