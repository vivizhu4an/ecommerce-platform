package com.keranzhu.shop.cart;

import com.keranzhu.shop.catalog.ProductService;
import com.keranzhu.shop.catalog.ProductView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class CartService {

    public record CartLine(ProductView product, int quantity, BigDecimal lineTotal) {
    }

    public record CartView(List<CartLine> items, BigDecimal total) {
    }

    private final CartItemRepository cart;
    private final ProductService products;

    public CartService(CartItemRepository cart, ProductService products) {
        this.cart = cart;
        this.products = products;
    }

    @Transactional(readOnly = true)
    public CartView view(Long userId) {
        List<CartLine> lines = cart.findByUserIdOrderByProductId(userId).stream()
                .map(item -> {
                    ProductView p = products.get(item.getProductId());
                    return new CartLine(p, item.getQuantity(),
                            p.price().multiply(BigDecimal.valueOf(item.getQuantity())));
                })
                .toList();
        BigDecimal total = lines.stream().map(CartLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CartView(lines, total);
    }

    /** Sets the quantity of a product in the cart (0 removes it). */
    @Transactional
    public CartView setQuantity(Long userId, long productId, int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0");
        }
        products.get(productId); // 404 if the product does not exist
        if (quantity == 0) {
            cart.deleteItem(userId, productId);
        } else {
            CartItem item = cart.findByUserIdAndProductId(userId, productId)
                    .orElseGet(() -> new CartItem(userId, productId, quantity));
            item.setQuantity(quantity);
            cart.save(item);
        }
        return view(userId);
    }

    @Transactional
    public void clear(Long userId) {
        cart.deleteAllForUser(userId);
    }
}
