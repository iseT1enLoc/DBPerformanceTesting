package com.example.dbperformtest.model;

import com.oracle.nosql.spring.data.core.mapping.NosqlKey;

import java.io.Serializable;
import java.util.Objects;

public class OrderKey implements Serializable {

    @NosqlKey(shardKey = true, order = 1)
    private String customerId;

    @NosqlKey(shardKey = false, order = 2)
    private long orderId;

    public OrderKey() {}

    public OrderKey(String customerId, long orderId) {
        this.customerId = customerId;
        this.orderId = orderId;
    }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public long getOrderId() { return orderId; }
    public void setOrderId(long orderId) { this.orderId = orderId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderKey k)) return false;
        return orderId == k.orderId && Objects.equals(customerId, k.customerId);
    }

    @Override
    public int hashCode() { return Objects.hash(customerId, orderId); }

    @Override
    public String toString() { return customerId + "/" + orderId; }
}
