package com.keranzhu.shop.catalog;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Set;

/**
 * Evicts product cache entries <b>after</b> the surrounding transaction commits.
 *
 * <p>Evicting before commit opens a race: another request can miss the cache, read the
 * old row from MySQL (not yet committed) and put stale data back into Redis.
 * Evicting after commit guarantees the next read sees the new row.</p>
 */
@Component
public class ProductCacheEvictor {

    public static final String CACHE = "products";

    private final CacheManager cacheManager;

    public ProductCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public void evictAfterCommit(Collection<Long> productIds) {
        Set<Long> ids = Set.copyOf(productIds);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(ids);
                }
            });
        } else {
            evictNow(ids);
        }
    }

    private void evictNow(Set<Long> ids) {
        Cache cache = cacheManager.getCache(CACHE);
        if (cache != null) {
            ids.forEach(cache::evict);
        }
    }
}
