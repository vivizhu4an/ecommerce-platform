package com.keranzhu.shop.cart;

import com.keranzhu.shop.auth.AuthPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    public record SetQuantityRequest(@NotNull Long productId, @Min(0) @Max(99) int quantity) {
    }

    private final CartService service;

    public CartController(CartService service) {
        this.service = service;
    }

    @GetMapping
    public CartService.CartView view(@AuthenticationPrincipal AuthPrincipal user) {
        return service.view(user.userId());
    }

    @PutMapping("/items")
    public CartService.CartView setQuantity(@AuthenticationPrincipal AuthPrincipal user,
                                            @Valid @RequestBody SetQuantityRequest req) {
        return service.setQuantity(user.userId(), req.productId(), req.quantity());
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal AuthPrincipal user) {
        service.clear(user.userId());
    }
}
