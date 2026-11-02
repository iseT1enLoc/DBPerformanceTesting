package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import oracle.nosql.driver.Consistency;
import oracle.nosql.driver.Durability;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.ops.GetRequest;
import oracle.nosql.driver.ops.GetResult;
import oracle.nosql.driver.ops.PrepareRequest;
import oracle.nosql.driver.ops.PreparedStatement;
import oracle.nosql.driver.ops.PutRequest;
import oracle.nosql.driver.ops.QueryRequest;
import oracle.nosql.driver.ops.TableRequest;
import oracle.nosql.driver.ops.TableResult;
import oracle.nosql.driver.ops.WriteMultipleRequest;
import oracle.nosql.driver.ops.WriteMultipleResult;
import oracle.nosql.driver.values.DoubleValue;
import oracle.nosql.driver.values.MapValue;
import oracle.nosql.driver.values.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Oracle NoSQL SDK (nosqldriver): connects through the HTTP proxy. */
public class SdkClientImpl implements NoSqlClient {

    private final NoSQLHandle handle;
    private final Map<String, PreparedStatement> statements = new ConcurrentHashMap<>();

    public SdkClientImpl(NoSQLHandle handle) {
        this.handle = handle;
    }

    @Override
    public String name() { return "sdk"; }

    @Override
    public void put(String table, Order order, DurabilityLevel durability) {
        handle.put(new PutRequest()
                .setTableName(table)
                .setValue(toRow(order))
                .setDurability(durability(durability)));
    }

    @Override
    public Order get(String table, OrderKey key, ConsistencyLevel consistency) {
        GetResult result = handle.get(new GetRequest()
                .setTableName(table)
                .setKey(toKeyMap(key))
                .setConsistency(consistency(consistency)));
        return result.getValue() == null ? null : toOrder(result.getValue());
    }

    @Override
    public void putBulk(String table, List<Order> orders, DurabilityLevel durability) {
        for (Order o : orders) {
            put(table, o, durability);
        }
    }

    @Override
    public void putBatch(String table, List<Order> orders, DurabilityLevel durability) {
        WriteMultipleRequest req = new WriteMultipleRequest()
                .setDurability(durability(durability));
        for (Order o : orders) {
            req.add(new PutRequest().setTableName(table).setValue(toRow(o)), true);
        }
        WriteMultipleResult result = handle.writeMultiple(req);
        if (!result.getSuccess()) {
            throw new IllegalStateException("writeMultiple failed at index "
                    + result.getFailedOperationIndex());
        }
    }

    @Override
    public List<Order> getBatch(String table, String customerId, ConsistencyLevel consistency) {
        return queryByCustomer(table, customerId, consistency);
    }

    @Override
    public List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency) {
        PreparedStatement stmt = prepared(
                "DECLARE $cid STRING; SELECT * FROM " + table + " WHERE customerId = $cid");
        stmt.setVariable("$cid", new StringValue(customerId));
        return runQuery(stmt, consistency);
    }

    @Override
    public List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency) {
        PreparedStatement stmt = prepared(
                "DECLARE $lo DOUBLE; DECLARE $hi DOUBLE;"
                + " SELECT * FROM " + table + " WHERE amount >= $lo AND amount <= $hi");
        stmt.setVariable("$lo", new DoubleValue(lo));
        stmt.setVariable("$hi", new DoubleValue(hi));
        return runQuery(stmt, consistency);
    }

    /** Runs a DDL statement synchronously (CREATE TABLE, CREATE INDEX, DROP …). */
    public void executeDdl(String ddl) {
        try {
            TableResult result = handle.tableRequest(new TableRequest().setStatement(ddl));
            result.waitForCompletion(handle, 60_000, 1_000);
        } catch (Exception e) {
            throw new RuntimeException("DDL failed: " + ddl, e);
        }
    }

    private List<Order> runQuery(PreparedStatement stmt, ConsistencyLevel consistency) {
        List<Order> orders = new ArrayList<>();
        try (QueryRequest req = new QueryRequest()
                .setPreparedStatement(stmt)
                .setConsistency(consistency(consistency))) {
            do {
                for (MapValue v : handle.query(req).getResults()) {
                    orders.add(toOrder(v));
                }
            } while (!req.isDone());
        }
        return orders;
    }

    private PreparedStatement prepared(String sql) {
        return statements.computeIfAbsent(sql, s ->
                handle.prepare(new PrepareRequest().setStatement(s)).getPreparedStatement()
        ).copyStatement();
    }

    private static MapValue toKeyMap(OrderKey key) {
        return new MapValue()
                .put("customerId", key.customerId())
                .put("orderId", key.orderId());
    }

    private static MapValue toRow(Order o) {
        return new MapValue()
                .put("customerId", o.customerId())
                .put("orderId", o.orderId())
                .put("amount", o.amount())
                .put("payload", o.payload());
    }

    private static Order toOrder(MapValue v) {
        return new Order(
                v.getString("customerId"),
                v.getLong("orderId"),
                v.getDouble("amount"),
                v.getString("payload"));
    }

    private static Durability durability(DurabilityLevel level) {
        return switch (level) {
            case COMMIT_NO_SYNC -> Durability.COMMIT_NO_SYNC;
            case COMMIT_SYNC    -> Durability.COMMIT_SYNC;
        };
    }

    private static Consistency consistency(ConsistencyLevel level) {
        return switch (level) {
            case NONE_REQUIRED -> Consistency.EVENTUAL;
            case ABSOLUTE      -> Consistency.ABSOLUTE;
        };
    }
}
