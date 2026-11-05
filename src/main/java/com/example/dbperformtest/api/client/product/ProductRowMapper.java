package com.example.dbperformtest.api.client.product;

import com.example.dbperformtest.api.client.RowMapper;
import com.example.dbperformtest.model.Product;
import com.example.dbperformtest.model.ProductKey;
import oracle.nosql.driver.values.MapValue;

public class ProductRowMapper implements RowMapper<Product, ProductKey> {

    @Override
    public MapValue toRow(Product p) {
        return new MapValue()
                .put("productId", p.productId())
                .put("name",      p.name())
                .put("price",     p.price());
    }

    @Override
    public MapValue toKey(ProductKey key) {
        return new MapValue().put("productId", key.productId());
    }

    @Override
    public Product fromRow(MapValue v) {
        return new Product(
                v.getString("productId"),
                v.getString("name"),
                v.getDouble("price"));
    }

    @Override
    public String shardKeyField() { return "productId"; }

    @Override
    public String shardKeyValue(Product p) { return p.productId(); }

    @Override
    public String[] columns() { return new String[]{"productId", "name", "price"}; }
}
