package com.example.dbperformtest.model;

public record Order(String customerId, long orderId, double amount, String payload) {
}
