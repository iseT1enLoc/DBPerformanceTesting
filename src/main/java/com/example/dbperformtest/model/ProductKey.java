package com.example.dbperformtest.model;

public class ProductKey {

    private final String productId;

    public ProductKey(String productId) {
        this.productId = productId;
    }

    public String productId() { return productId; }
}
