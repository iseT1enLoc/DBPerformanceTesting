package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
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
import oracle.nosql.driver.values.MapValue;

import java.util.stream.Collectors;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Generic Oracle NoSQL SDK implementation. Use a domain-specific subclass (or extend
 * directly) to add entity-specific query methods on top of the standard CRUD operations.
 *
 * @param <T> entity type
 * @param <K> key type
 */
public class SdkClientImpl<T, K> implements NoSqlClient<T, K> {

    public static final int DEFAULT_MAX_BATCH_SIZE = 25;

    private final NoSQLHandle handle;
    private final RowMapper<T, K> mapper;
    private final int maxBatchSize;
    private final Map<String, PreparedStatement> statements = new ConcurrentHashMap<>();
    private final Set<String> allowedTables;

    public SdkClientImpl(NoSQLHandle handle, RowMapper<T, K> mapper, String... allowedTables) {
        this(handle, mapper, DEFAULT_MAX_BATCH_SIZE, allowedTables);
    }

    public SdkClientImpl(NoSQLHandle handle, RowMapper<T, K> mapper, int maxBatchSize, String... allowedTables) {
        if (maxBatchSize < 1) throw new IllegalArgumentException("maxBatchSize must be >= 1");
        this.handle       = handle;
        this.mapper       = mapper;
        this.maxBatchSize = maxBatchSize;
        this.allowedTables = Set.of(allowedTables);
    }

    protected String table(String name) {
        if (!allowedTables.contains(name))
            throw new IllegalArgumentException("Unknown table: " + name);
        return name;
    }

    @Override
    public String name() { return "sdk"; }

    @Override
    public void put(String table, T entity, DurabilityLevel durability) {
        handle.put(new PutRequest()
                .setTableName(table(table))
                .setValue(mapper.toRow(entity))
                .setDurability(durability(durability)));
    }

    @Override
    public T get(String table, K key, ConsistencyLevel consistency) {
        GetResult result = handle.get(new GetRequest()
                .setTableName(table(table))
                .setKey(mapper.toKey(key))
                .setConsistency(consistency(consistency)));
        return result.getValue() == null ? null : mapper.fromRow(result.getValue());
    }

    /**
     * Issues an individual {@code put} for each entity.
     * Use this when entities span different shard keys — {@code writeMultiple} requires
     * all rows in a request to share the same shard key, so individual puts are the only option.
     */
    @Override
    public void putBulk(String table, List<T> entities, DurabilityLevel durability) {
        table(table);
        for (T entity : entities) {
            put(table, entity, durability);
        }
    }

    /**
     * Splits {@code entities} into chunks of {@link #maxBatchSize} and sends each chunk
     * as a single {@code writeMultiple} request. All rows in a chunk must share the same
     * shard key (Oracle NoSQL requirement).
     */
    @Override
    public void putBatch(String table, List<T> entities, DurabilityLevel durability) {
        table(table);
        for (int i = 0; i < entities.size(); i += maxBatchSize) {
            List<T> chunk = entities.subList(i, Math.min(i + maxBatchSize, entities.size()));
            WriteMultipleRequest req = new WriteMultipleRequest()
                    .setDurability(durability(durability));
            for (T entity : chunk) {
                req.add(new PutRequest()
                        .setTableName(table)
                        .setValue(mapper.toRow(entity)), true);
            }
            WriteMultipleResult result = handle.writeMultiple(req);
            if (!result.getSuccess()) {
                throw new IllegalStateException("writeMultiple failed at chunk starting at index "
                        + i + ", operation index " + result.getFailedOperationIndex());
            }
        }
    }

    /**
     * Issues an individual {@code get} request for each key and returns the found rows.
     * Missing keys are silently skipped — the result list never contains nulls.
     */
    @Override
    public List<T> getBatch(String table, List<K> keys, ConsistencyLevel consistency) {
        table(table);
        List<T> results = new ArrayList<>(keys.size());
        for (K key : keys) {
            T value = get(table, key, consistency);
            if (value != null) results.add(value);
        }
        return results;
    }

    /**
     * Fetches each key using a native {@link GetRequest} issued in parallel across a fixed
     * thread pool so network round-trips overlap. Missing keys are silently skipped.
     */
    @Override
    public List<T> getListByPrimaryKey(String table, List<K> keys, ConsistencyLevel consistency) {
        table(table);
        Consistency c = consistency(consistency);
        List<java.util.concurrent.Future<T>> futures = new ArrayList<>(keys.size());
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(
                        Math.min(keys.size(), Runtime.getRuntime().availableProcessors() * 2));
        try {
            for (K key : keys) {
                futures.add(pool.submit(() -> {
                    GetResult r = handle.get(new GetRequest()
                            .setTableName(table)
                            .setKey(mapper.toKey(key))
                            .setConsistency(c));
                    return r.getValue() == null ? null : mapper.fromRow(r.getValue());
                }));
            }
            List<T> results = new ArrayList<>(keys.size());
            for (java.util.concurrent.Future<T> f : futures) {
                T value = f.get();
                if (value != null) results.add(value);
            }
            return results;
        } catch (Exception e) {
            throw new RuntimeException("getListByPrimaryKey failed", e);
        } finally {
            pool.shutdown();
        }
    }

    /**
     * SQL-based lookup using an inline {@code IN} clause built from the key list.
     * A new QueryRequest is issued per call (no prepared-statement cache) because
     * the literal list changes with each invocation.
     */
    @Override
    public List<T> getListById(String table, List<String> keys, ConsistencyLevel consistency) {
        table(table);
        String field = mapper.shardKeyField();
        String inList = keys.stream()
                .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(", "));
        String cols = String.join(", ", mapper.columns());
        String sql = "SELECT " + cols + " FROM " + table + " WHERE " + field + " IN (" + inList + ")";
        System.out.printf("[getListById] %d keys, SQL: %s%n", keys.size(), sql);
        List<T> results = new ArrayList<>();
        try (QueryRequest req = new QueryRequest()
                .setStatement(sql)
                .setConsistency(consistency(consistency))) {
            do {
                for (MapValue v : handle.query(req).getResults()) {
                    results.add(mapper.fromRow(v));
                }
            } while (!req.isDone());
        }
        return results;
    }

    /**
     * Writes the given entities in a single {@code writeMultiple} request.
     * All entities must share the same shard key — partitioning is the caller's responsibility.
     */
    @Override
    public void writeBulk(String table, List<T> entities, ConsistencyLevel consistency) {
        if (entities.isEmpty()) return;
        table(table);
        WriteMultipleRequest req = new WriteMultipleRequest();
        for (T entity : entities) {
            req.add(new PutRequest()
                    .setTableName(table)
                    .setValue(mapper.toRow(entity)), true);
        }
        WriteMultipleResult result = handle.writeMultiple(req);
        if (!result.getSuccess()) {
            throw new IllegalStateException(
                    "writeBulk: writeMultiple failed at operation index "
                    + result.getFailedOperationIndex());
        }
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

    /**
     * Executes a prepared SQL query. Subclasses call this to implement domain-specific queries.
     *
     * @param sql    parameterized SQL (use DECLARE variables)
     * @param params consumer that binds variables on the prepared statement
     */
    protected List<T> runQuery(String sql, Consumer<PreparedStatement> params, ConsistencyLevel consistency) {
        PreparedStatement stmt = prepared(sql);
        params.accept(stmt);
        return runQuery(stmt, consistency);
    }

    protected List<T> runQuery(PreparedStatement stmt, ConsistencyLevel consistency) {
        List<T> results = new ArrayList<>();
        try (QueryRequest req = new QueryRequest()
                .setPreparedStatement(stmt)
                .setConsistency(consistency(consistency))) {
            do {
                for (MapValue v : handle.query(req).getResults()) {
                    results.add(mapper.fromRow(v));
                }
            } while (!req.isDone());
        }
        return results;
    }

    protected PreparedStatement prepared(String sql) {
        return statements.computeIfAbsent(sql, s ->
                handle.prepare(new PrepareRequest().setStatement(s)).getPreparedStatement()
        ).copyStatement();
    }

    protected static Durability durability(DurabilityLevel level) {
        return switch (level) {
            case COMMIT_NO_SYNC -> Durability.COMMIT_NO_SYNC;
            case COMMIT_SYNC    -> Durability.COMMIT_SYNC;
        };
    }

    protected static Consistency consistency(ConsistencyLevel level) {
        return switch (level) {
            case NONE_REQUIRED -> Consistency.EVENTUAL;
            case ABSOLUTE      -> Consistency.ABSOLUTE;
        };
    }
}
