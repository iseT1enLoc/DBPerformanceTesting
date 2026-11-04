package com.example.dbperformtest.client.order;

import com.example.dbperformtest.client.SdkClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.values.DoubleValue;
import oracle.nosql.driver.values.StringValue;

import java.util.List;

/** Oracle NoSQL SDK client for the Order entity with domain-specific query methods. */
public class OrderSdkClientImpl extends SdkClientImpl<Order, OrderKey> implements OrderNoSqlClient {

    public OrderSdkClientImpl(NoSQLHandle handle, String... allowedTables) {
        super(handle, new OrderRowMapper(), allowedTables);
    }

    @Override
    public List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency) {
        return runQuery(
                "DECLARE $cid STRING; SELECT * FROM " + table(table) + " WHERE customerId = $cid",
                stmt -> stmt.setVariable("$cid", new StringValue(customerId)),
                consistency);
    }

    @Override
    public List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency) {
        return runQuery(
                "DECLARE $lo DOUBLE; DECLARE $hi DOUBLE;"
                + " SELECT * FROM " + table(table) + " WHERE amount >= $lo AND amount <= $hi",
                stmt -> {
                    stmt.setVariable("$lo", new DoubleValue(lo));
                    stmt.setVariable("$hi", new DoubleValue(hi));
                },
                consistency);
    }
}
