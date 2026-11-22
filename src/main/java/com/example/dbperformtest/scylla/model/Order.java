package com.example.dbperformtest.scylla.model;

public record Order(String customerId, String orderId, String amount) {}
