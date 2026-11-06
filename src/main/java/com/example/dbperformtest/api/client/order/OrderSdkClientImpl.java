package com.example.dbperformtest.api.client.order;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.values.DoubleValue;
import oracle.nosql.driver.values.StringValue;

import java.util.List;

/** Oracle NoSQL SDK client for the Order entity with domain-specific query methods. */
public class OrderSdkClientImpl extends SdkClientImpl<Order, OrderKey> implements OrderNoSqlClient {

    private static final String COLS = "customerId, orderId, amount, payload";

    private final String defaultTable;

    public OrderSdkClientImpl(NoSQLHandle handle, String... allowedTables) {
        super(handle, new OrderRowMapper(), allowedTables);
        this.defaultTable = allowedTables[0];
    }

    @Override
    public List<Order> queryByCustomer(String customerId, ConsistencyLevel consistency) {
        return runQuery(
                "DECLARE $cid STRING; SELECT " + COLS + " FROM " + table(defaultTable) + " WHERE customerId = $cid",
                stmt -> stmt.setVariable("$cid", new StringValue(customerId)),
                consistency);
    }

    @Override
    public List<Order> queryByAmount(double lo, double hi, ConsistencyLevel consistency) {
        return runQuery(
                "DECLARE $lo DOUBLE; DECLARE $hi DOUBLE;"
                + " SELECT " + COLS + " FROM " + table(defaultTable) + " WHERE amount >= $lo AND amount <= $hi",
                stmt -> {
                    stmt.setVariable("$lo", new DoubleValue(lo));
                    stmt.setVariable("$hi", new DoubleValue(hi));
                },
                consistency);
    }
}
