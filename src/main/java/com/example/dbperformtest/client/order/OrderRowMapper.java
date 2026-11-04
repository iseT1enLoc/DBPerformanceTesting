package com.example.dbperformtest.client.order;

import com.example.dbperformtest.client.RowMapper;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import oracle.nosql.driver.values.MapValue;

public class OrderRowMapper implements RowMapper<Order, OrderKey> {

    @Override
    public MapValue toRow(Order o) {
        return new MapValue()
                .put("customerId", o.customerId())
                .put("orderId", o.orderId())
                .put("amount", o.amount())
                .put("payload", o.payload());
    }

    @Override
    public MapValue toKey(OrderKey key) {
        return new MapValue()
                .put("customerId", key.customerId())
                .put("orderId", key.orderId());
    }

    @Override
    public Order fromRow(MapValue v) {
        return new Order(
                v.getString("customerId"),
                v.getLong("orderId"),
                v.getDouble("amount"),
                v.getString("payload"));
    }

    @Override
    public String shardKeyField() { return "customerId"; }

    @Override
    public String shardKeyValue(Order entity) { return entity.customerId(); }

    @Override
    public String[] columns() { return new String[]{"customerId", "orderId", "amount", "payload"}; }
}
