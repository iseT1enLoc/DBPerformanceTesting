package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import oracle.kv.BulkWriteOptions;
import oracle.kv.Consistency;
import oracle.kv.Durability;
import oracle.kv.EntryStream;
import oracle.kv.KVStore;
import oracle.kv.StatementResult;
import oracle.kv.query.BoundStatement;
import oracle.kv.query.ExecuteOptions;
import oracle.kv.query.PreparedStatement;
import oracle.kv.table.PrimaryKey;
import oracle.kv.table.ReadOptions;
import oracle.kv.table.RecordValue;
import oracle.kv.table.Row;
import oracle.kv.table.Table;
import oracle.kv.table.TableAPI;
import oracle.kv.table.TableOpExecutionException;
import oracle.kv.table.TableOperation;
import oracle.kv.table.TableOperationFactory;
import oracle.kv.table.WriteOptions;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** KV direct driver (kvclient): talks to the storage nodes directly. */
public class KvClientImpl implements NoSqlClient {

    private final KVStore store;
    private final TableAPI tableAPI;
    private final Map<String, Table> tables = new ConcurrentHashMap<>();
    private final Map<String, PreparedStatement> statements = new ConcurrentHashMap<>();

    public KvClientImpl(KVStore store) {
        this.store = store;
        this.tableAPI = store.getTableAPI();
    }

    @Override
    public String name() {
        return "kvclient";
    }

    @Override
    public void put(String table, Order order, DurabilityLevel durability) {
        tableAPI.put(toRow(table(table), order), null, writeOptions(durability));
    }

    @Override
    public Order get(String table, String customerId, long orderId, ConsistencyLevel consistency) {
        PrimaryKey pk = table(table).createPrimaryKey();
        pk.put("customerId", customerId);
        pk.put("orderId", orderId);
        Row row = tableAPI.get(pk, readOptions(consistency));
        return row == null ? null : toOrder(row);
    }

    @Override
    public void putBatch(String table, List<Order> orders, DurabilityLevel durability) {
        Table t = table(table);
        TableOperationFactory factory = tableAPI.getTableOperationFactory();
        List<TableOperation> ops = new ArrayList<>(orders.size());
        for (Order order : orders) {
            ops.add(factory.createPut(toRow(t, order), null, true));
        }
        try {
            tableAPI.execute(ops, writeOptions(durability));
        } catch (TableOpExecutionException e) {
            throw new IllegalStateException("Batch put failed", e);
        }
    }

    @Override
    public void putBulk(String table, List<Order> orders, DurabilityLevel durability) {
        Table t = table(table);
        BulkWriteOptions opts = new BulkWriteOptions(kvDurability(durability), 0, null);
        List<EntryStream<Row>> streams = new ArrayList<>();
        streams.add(new OrderStream(t, orders));
        tableAPI.put(streams, opts);
    }

    @Override
    public List<Order> getBatch(String table, String customerId, ConsistencyLevel consistency) {
        PrimaryKey pk = table(table).createPrimaryKey();
        pk.put("customerId", customerId);
        List<Row> rows = tableAPI.multiGet(pk, null, readOptions(consistency));
        List<Order> orders = new ArrayList<>(rows.size());
        for (Row row : rows) {
            orders.add(toOrder(row));
        }
        return orders;
    }

    @Override
    public List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency) {
        BoundStatement bound = prepared(Sql.byCustomer(table)).createBoundStatement();
        bound.setVariable("$cid", customerId);
        return runQuery(bound, consistency);
    }

    @Override
    public List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency) {
        BoundStatement bound = prepared(Sql.byAmount(table)).createBoundStatement();
        bound.setVariable("$lo", lo);
        bound.setVariable("$hi", hi);
        return runQuery(bound, consistency);
    }

    /** Runs DDL or other admin statements synchronously. */
    public void executeDdl(String statement) {
        store.executeSync(statement);
    }

    private List<Order> runQuery(BoundStatement bound, ConsistencyLevel consistency) {
        ExecuteOptions options = new ExecuteOptions().setConsistency(consistency(consistency));
        List<Order> orders = new ArrayList<>();
        StatementResult result = store.executeSync(bound, options);
        try {
            for (RecordValue record : result) {
                orders.add(toOrder(record));
            }
        } finally {
            result.close();
        }
        return orders;
    }

    private Table table(String name) {
        return tables.computeIfAbsent(name, n -> {
            Table t = tableAPI.getTable(n);
            if (t == null) {
                throw new IllegalStateException("Table not found: " + n);
            }
            return t;
        });
    }

    private PreparedStatement prepared(String sql) {
        return statements.computeIfAbsent(sql, store::prepare);
    }

    private static Row toRow(Table table, Order order) {
        Row row = table.createRow();
        row.put("customerId", order.customerId());
        row.put("orderId", order.orderId());
        row.put("amount", order.amount());
        row.put("payload", order.payload());
        return row;
    }

    private static Order toOrder(RecordValue record) {
        return new Order(
                record.get("customerId").asString().get(),
                record.get("orderId").asLong().get(),
                record.get("amount").asDouble().get(),
                record.get("payload").asString().get());
    }

    private static Durability kvDurability(DurabilityLevel level) {
        return switch (level) {
            case COMMIT_NO_SYNC -> Durability.COMMIT_NO_SYNC;
            case COMMIT_SYNC -> Durability.COMMIT_SYNC;
        };
    }

    private static WriteOptions writeOptions(DurabilityLevel level) {
        return new WriteOptions().setDurability(switch (level) {
            case COMMIT_NO_SYNC -> Durability.COMMIT_NO_SYNC;
            case COMMIT_SYNC -> Durability.COMMIT_SYNC;
        });
    }

    private static ReadOptions readOptions(ConsistencyLevel level) {
        return new ReadOptions(consistency(level), 0, null);
    }

    private static Consistency consistency(ConsistencyLevel level) {
        return switch (level) {
            case NONE_REQUIRED -> Consistency.NONE_REQUIRED;
            case ABSOLUTE -> Consistency.ABSOLUTE;
        };
    }

    private static class OrderStream implements EntryStream<Row> {
        private final Table table;
        private final Iterator<Order> it;

        OrderStream(Table table, List<Order> orders) {
            this.table = table;
            this.it = orders.iterator();
        }

        @Override
        public Row getNext() {
            return it.hasNext() ? toRow(table, it.next()) : null;
        }

        @Override
        public String name() {
            return "orders";
        }

        @Override
        public void keyExists(Row row) {}

        @Override
        public void completed() {}

        @Override
        public void catchException(RuntimeException e, Row row) {
            throw e;
        }
    }
}
