package com.keranzhu.shop.common;

public class InsufficientStockException extends RuntimeException {

    private final long productId;

    public InsufficientStockException(long productId) {
        super("Not enough stock for product " + productId);
        this.productId = productId;
    }

    public long getProductId() {
        return productId;
    }
}
