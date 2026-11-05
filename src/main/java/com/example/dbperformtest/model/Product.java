package com.example.dbperformtest.model;

public class Product {

    private final String productId;
    private final String name;
    private final double price;

    public Product(String productId, String name, double price) {
        this.productId = productId;
        this.name      = name;
        this.price     = price;
    }

    public String productId() { return productId; }
    public String name()      { return name; }
    public double price()     { return price; }

    @Override
    public String toString() {
        return "Product{id='" + productId + "', name='" + name + "', price=" + price + "}";
    }
}
