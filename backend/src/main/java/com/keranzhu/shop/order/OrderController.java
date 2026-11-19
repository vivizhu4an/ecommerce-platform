package com.keranzhu.shop.order;

import com.keranzhu.shop.auth.AuthPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    /**
     * Checks out the caller's cart. Clients generate one Idempotency-Key per checkout attempt
     * and resend the same key on retry; the same order is returned every time.
     */
    @PostMapping("/checkout")
    public OrderView checkout(@AuthenticationPrincipal AuthPrincipal user,
                              @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.checkout(user.userId(), idempotencyKey);
    }

    @GetMapping
    public List<OrderView> list(@AuthenticationPrincipal AuthPrincipal user) {
        return service.list(user.userId());
    }

    @GetMapping("/{id}")
    public OrderView get(@AuthenticationPrincipal AuthPrincipal user, @PathVariable Long id) {
        return service.get(user.userId(), id);
    }
}
