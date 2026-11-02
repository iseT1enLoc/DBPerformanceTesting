package com.example.dbperformtest.model;

public class Order {

    private String customerId;
    private long   orderId;
    private double amount;
    private String payload;

    public Order() {}

    public Order(String customerId, long orderId, double amount, String payload) {
        this.customerId = customerId;
        this.orderId    = orderId;
        this.amount     = amount;
        this.payload    = payload;
    }

    public String customerId() { return customerId; }
    public long   orderId()    { return orderId; }
    public double amount()     { return amount; }
    public String payload()    { return payload; }

    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public void setOrderId(long orderId)         { this.orderId = orderId; }
    public void setAmount(double amount)         { this.amount = amount; }
    public void setPayload(String payload)       { this.payload = payload; }
}
