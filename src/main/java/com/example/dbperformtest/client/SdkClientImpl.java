package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import oracle.nosql.driver.Consistency;
import oracle.nosql.driver.Durability;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.ops.GetRequest;
import oracle.nosql.driver.ops.GetResult;
import oracle.nosql.driver.ops.PrepareRequest;
import oracle.nosql.driver.ops.PreparedStatement;
import oracle.nosql.driver.ops.PutRequest;
import oracle.nosql.driver.ops.QueryRequest;
import oracle.nosql.driver.ops.QueryResult;
import oracle.nosql.driver.ops.WriteMultipleRequest;
import oracle.nosql.driver.ops.WriteMultipleResult;
import oracle.nosql.driver.values.DoubleValue;
import oracle.nosql.driver.values.MapValue;
import oracle.nosql.driver.values.StringValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Oracle NoSQL SDK (nosqldriver): talks to the HTTP proxy. The SDK has no multiGet, so
 * {@link #getBatch} uses the same prepared shard-key query as {@link #queryByCustomer}.
 */
public class SdkClientImpl implements NoSqlClient {

    private final NoSQLHandle handle;
    private final Map<String, PreparedStatement> statements = new ConcurrentHashMap<>();

    public SdkClientImpl(NoSQLHandle handle) {
        this.handle = handle;
    }

    @Override
    public String name() {
        return "sdk";
    }

    @Override
    public void put(String table, Order order, DurabilityLevel durability) {
        handle.put(putRequest(table, order).setDurability(durability(durability)));
    }

    @Override
    public Order get(String table, String customerId, long orderId, ConsistencyLevel consistency) {
        MapValue key = new MapValue().put("customerId", customerId).put("orderId", orderId);
        GetResult result = handle.get(new GetRequest()
                .setTableName(table)
                .setKey(key)
                .setConsistency(consistency(consistency)));
        return result.getValue() == null ? null : toOrder(result.getValue());
    }

    @Override
    public void putBatch(String table, List<Order> orders, DurabilityLevel durability) {
        WriteMultipleRequest request = new WriteMultipleRequest().setDurability(durability(durability));
        for (Order order : orders) {
            request.add(putRequest(table, order), true);
        }
        WriteMultipleResult result = handle.writeMultiple(request);
        if (!result.getSuccess()) {
            throw new IllegalStateException("writeMultiple failed at index " + result.getFailedOperationIndex());
        }
    }

    @Override
    public List<Order> getBatch(String table, String customerId, ConsistencyLevel consistency) {
        return queryByCustomer(table, customerId, consistency);
    }

    @Override
    public List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency) {
        PreparedStatement statement = prepared(Sql.byCustomer(table));
        statement.setVariable("$cid", new StringValue(customerId));
        return runQuery(statement, consistency);
    }

    @Override
    public List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency) {
        PreparedStatement statement = prepared(Sql.byAmount(table));
        statement.setVariable("$lo", new DoubleValue(lo));
        statement.setVariable("$hi", new DoubleValue(hi));
        return runQuery(statement, consistency);
    }

    private List<Order> runQuery(PreparedStatement statement, ConsistencyLevel consistency) {
        List<Order> orders = new ArrayList<>();
        try (QueryRequest request = new QueryRequest()
                .setPreparedStatement(statement)
                .setConsistency(consistency(consistency))) {
            do {
                QueryResult result = handle.query(request);
                for (MapValue value : result.getResults()) {
                    orders.add(toOrder(value));
                }
            } while (!request.isDone());
        }
        return orders;
    }

    /** Returns a private copy of the cached statement, since bound variables are per statement. */
    private PreparedStatement prepared(String sql) {
        return statements.computeIfAbsent(sql, s ->
                handle.prepare(new PrepareRequest().setStatement(s)).getPreparedStatement()
        ).copyStatement();
    }

    private static PutRequest putRequest(String table, Order order) {
        MapValue value = new MapValue()
                .put("customerId", order.customerId())
                .put("orderId", order.orderId())
                .put("amount", order.amount())
                .put("payload", order.payload());
        return new PutRequest().setTableName(table).setValue(value);
    }

    private static Order toOrder(MapValue value) {
        return new Order(
                value.getString("customerId"),
                value.getLong("orderId"),
                value.getDouble("amount"),
                value.getString("payload"));
    }

    private static Durability durability(DurabilityLevel level) {
        return switch (level) {
            case COMMIT_NO_SYNC -> Durability.COMMIT_NO_SYNC;
            case COMMIT_SYNC -> Durability.COMMIT_SYNC;
        };
    }

    private static Consistency consistency(ConsistencyLevel level) {
        return switch (level) {
            case NONE_REQUIRED -> Consistency.EVENTUAL;
            case ABSOLUTE -> Consistency.ABSOLUTE;
        };
    }
}
