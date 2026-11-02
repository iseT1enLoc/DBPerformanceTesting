package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;

import java.util.List;

/**
 * Operations benchmarked against Oracle NoSQL. Implementations must be thread-safe
 * and must fully convert results to {@link Order} so deserialization cost is measured equally.
 */
public interface NoSqlClient {

    String name();

    void put(String table, Order order, DurabilityLevel durability);

    Order get(String table, String customerId, long orderId, ConsistencyLevel consistency);

    /** Batch write for rows that share the same shard key (customerId). */
    void putBatch(String table, List<Order> orders, DurabilityLevel durability);

    /** All rows for one shard key. */
    List<Order> getBatch(String table, String customerId, ConsistencyLevel consistency);

    /** Single-shard SQL query: rows for one customerId. */
    List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency);

    /** All-shard SQL query: rows with amount in [lo, hi], served by a secondary index. */
    List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency);
}
