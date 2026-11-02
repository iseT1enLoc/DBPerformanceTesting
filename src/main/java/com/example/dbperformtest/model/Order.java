package com.example.dbperformtest.model;

import com.oracle.nosql.spring.data.core.mapping.NosqlId;
import com.oracle.nosql.spring.data.core.mapping.NosqlTable;

@NosqlTable(tableName = "orders", autoCreateTable = true)
public class Order {

    @NosqlId
    private OrderKey id;
    private double amount;
    private String payload;

    public Order() {}

    public Order(String customerId, long orderId, double amount, String payload) {
        this.id = new OrderKey(customerId, orderId);
        this.amount = amount;
        this.payload = payload;
    }

    /** Record-style accessor kept for backward compatibility with existing benchmark code. */
    public String customerId() { return id != null ? id.getCustomerId() : null; }
    public long orderId()      { return id != null ? id.getOrderId() : 0L; }
    public double amount()     { return amount; }
    public String payload()    { return payload; }

    public OrderKey getId()              { return id; }
    public void setId(OrderKey id)       { this.id = id; }
    public void setAmount(double amount) { this.amount = amount; }
    public void setPayload(String p)     { this.payload = p; }
}
