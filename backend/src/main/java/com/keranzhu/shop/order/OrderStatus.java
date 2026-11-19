package com.keranzhu.shop.order;

public enum OrderStatus {
    /** Stock reserved and order stored; OrderCreated event not yet processed. */
    PENDING,
    /** The order-created consumer has processed the event. */
    CONFIRMED
}
