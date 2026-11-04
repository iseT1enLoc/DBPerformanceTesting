package com.example.dbperformtest.client.order;

import com.example.dbperformtest.client.NoSqlClient;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;

import java.util.List;

/** Order-specific NoSQL operations extending the generic CRUD contract. */
public interface OrderNoSqlClient extends NoSqlClient<Order, OrderKey> {

    /** Single-shard SQL query: all orders for one customerId. */
    List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency);

    /** Cross-shard SQL query: orders with amount in [lo, hi], served by a secondary index. */
    List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency);
}
