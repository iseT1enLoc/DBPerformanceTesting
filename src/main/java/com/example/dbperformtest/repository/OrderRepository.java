package com.example.dbperformtest.repository;

import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import com.oracle.nosql.spring.data.repository.NosqlRepository;

import java.util.List;

public interface OrderRepository extends NosqlRepository<Order, OrderKey> {

    /** All orders for one customer (shard-key query). */
    List<Order> findById_CustomerId(String customerId);

    /** Orders whose amount falls in [lo, hi]. */
    List<Order> findByAmountBetween(double lo, double hi);
}
