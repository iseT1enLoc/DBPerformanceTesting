package com.example.dbperformtest.model;

public class OrderKey {

    private final String customerId; // partition (shard) key
    private final long   orderId;    // sort key

    private OrderKey(String customerId, long orderId) {
        this.customerId = customerId;
        this.orderId    = orderId;
    }

    public static OrderKey of(String customerId, long orderId) {
        return new OrderKey(customerId, orderId);
    }

    public String customerId() { return customerId; }
    public long   orderId()    { return orderId; }

    @Override
    public String toString() { return customerId + "#" + orderId; }
}
