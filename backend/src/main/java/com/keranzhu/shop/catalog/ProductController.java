package com.keranzhu.shop.catalog;

import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class ProductController {

    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping("/api/products")
    public Page<ProductView> list(@PageableDefault(size = 20, sort = "id") Pageable pageable) {
        return service.list(pageable);
    }

    @GetMapping("/api/products/{id}")
    public ProductView get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping("/api/admin/products")
    @ResponseStatus(HttpStatus.CREATED)
    public ProductView create(@Valid @RequestBody ProductRequest req) {
        return service.create(req);
    }

    @PutMapping("/api/admin/products/{id}")
    public ProductView update(@PathVariable long id, @Valid @RequestBody ProductRequest req) {
        return service.update(id, req);
    }
}
