package com.keranzhu.shop.catalog;

import com.keranzhu.shop.common.ConflictException;
import com.keranzhu.shop.common.NotFoundException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository products;
    private final ProductCacheEvictor evictor;

    public ProductService(ProductRepository products, ProductCacheEvictor evictor) {
        this.products = products;
        this.evictor = evictor;
    }

    /** Single-product reads are the hot path, so they go through Redis (TTL in CacheConfig). */
    @Cacheable(cacheNames = ProductCacheEvictor.CACHE, key = "#id")
    @Transactional(readOnly = true)
    public ProductView get(long id) {
        return products.findById(id)
                .map(Product::toView)
                .orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
    }

    @Transactional(readOnly = true)
    public Page<ProductView> list(Pageable pageable) {
        return products.findAll(pageable).map(Product::toView);
    }

    @Transactional
    public ProductView create(ProductRequest req) {
        if (products.existsBySku(req.sku())) {
            throw new ConflictException("SKU already exists: " + req.sku());
        }
        Product p = products.save(new Product(req.sku(), req.name(), req.description(), req.price(), req.stock()));
        return p.toView();
    }

    @Transactional
    public ProductView update(long id, ProductRequest req) {
        Product p = products.findById(id)
                .orElseThrow(() -> new NotFoundException("Product " + id + " not found"));
        p.update(req.name(), req.description(), req.price(), req.stock());
        evictor.evictAfterCommit(List.of(id));
        return p.toView();
    }
}
