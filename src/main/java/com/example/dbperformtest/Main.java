package com.example.dbperformtest;

import com.example.dbperformtest.client.KvClientImpl;
import com.example.dbperformtest.client.NoSqlClient;
import com.example.dbperformtest.client.SdkClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.workload.OrderCsvReader;
import oracle.kv.KVStore;
import oracle.kv.KVStoreConfig;
import oracle.kv.KVStoreFactory;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.NoSQLHandleConfig;
import oracle.nosql.driver.NoSQLHandleFactory;
import oracle.nosql.driver.kv.StoreAccessTokenProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

public class Main {

    private static final String TABLE    = "orders";
    private static final String CSV_FILE = "data/orders.csv";
    private static final int    RECORDS    = 100_000;   // rows to load and test with
    private static final int    WARMUP     = 200;     // ops discarded before measuring
    private static final int    OPS        = 1_000;   // ops measured per scenario
    private static final int    BULK_BATCH = 1000;     // rows per putBulk call (cross-shard)
    private static final int    INSERT_RUNS = 5;      // repetitions for sequential-vs-bulk comparison

    private static final int    MAX_ATTEMPTS  = 5;
    private static final long   INITIAL_DELAY = 1_000; // ms

    public static void main(String[] args) throws Exception {
        // Connect with retry
        KVStore kvStore = retry("kvclient", () ->
                KVStoreFactory.getStore(new KVStoreConfig("kvstore", "localhost:5100")));
        NoSQLHandle sdkHandle = retry("sdk", () ->
                NoSQLHandleFactory.createNoSQLHandle(
                        new NoSQLHandleConfig("http://localhost:8180", new StoreAccessTokenProvider())));
        KvClientImpl kv  = new KvClientImpl(kvStore);
        SdkClientImpl sdk = new SdkClientImpl(sdkHandle);

        // Create table
        kv.executeDdl("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (customerId STRING, orderId LONG, amount DOUBLE, payload STRING,"
                + " PRIMARY KEY (SHARD(customerId), orderId))");
        kv.executeDdl("CREATE INDEX IF NOT EXISTS idx_amount ON " + TABLE + " (amount)");

        // Read CSV and load records into the table
        List<Order> orders = OrderCsvReader.read(Path.of(CSV_FILE), RECORDS);
        System.out.printf("Loaded %d records from %s%n%n", orders.size(), CSV_FILE);
        for (Order order : orders) {
            kv.put(TABLE, order, DurabilityLevel.COMMIT_NO_SYNC);
        }

        // Group orders by customer for batch operations (putBatch requires same shard key)
        List<List<Order>> batches = new ArrayList<>(groupByCustomer(orders).values());
        // Fixed-size cross-shard batches for putBulk
        List<List<Order>> bulkBatches = partition(orders, BULK_BATCH);

        // Benchmark both clients
        System.out.printf("%-10s  %-14s  %10s  %12s%n", "client", "operation", "ops/s", "avg ms/op");
        System.out.println("-".repeat(52));
        for (NoSqlClient client : List.of(kv, sdk)) {
            measure(client, "put_single", orders,
                    o -> client.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC));
            measureBatch(client, "put_batch", batches,
                    b -> client.putBatch(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC));
            // putBulk is kvclient-only (cross-shard parallel write; SDK has no equivalent)
            if (client == kv) {
                measureBatch(client, "put_bulk", bulkBatches,
                        b -> client.putBulk(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC));
            } else {
                System.out.printf("%-10s  %-14s  %10s  %12s%n", client.name(), "put_bulk", "n/a", "n/a");
            }
            measure(client, "get_single", orders,
                    o -> client.get(TABLE, o.customerId(), o.orderId(), ConsistencyLevel.NONE_REQUIRED));
            measure(client, "get_batch", orders,
                    o -> client.getBatch(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
            measure(client, "query_shard", orders,
                    o -> client.queryByCustomer(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
            System.out.println();
        }

        // Sequential vs putBulk: insert all RECORDS rows, compare total time (kvclient only)
        System.out.printf("%n--- Insert %d records: sequential vs putBulk(batch=%d) [kvclient, %d runs] ---%n",
                orders.size(), BULK_BATCH, INSERT_RUNS);
        System.out.printf("%-26s  %10s  %12s  %10s%n", "method", "rows/s", "total ms", "calls");
        System.out.println("-".repeat(64));
        compareTotal("sequential (put_single)", orders.size(), INSERT_RUNS, orders.size(), () -> {
            for (Order o : orders) kv.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC);
        });
        compareTotal("putBulk (batch=" + BULK_BATCH + ")", orders.size(), INSERT_RUNS, bulkBatches.size(), () -> {
            for (List<Order> b : bulkBatches) kv.putBulk(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC);
        });

        // Sequential read vs getBatch: read all RECORDS rows, compare total time — both clients
        int customers = batches.size();
        for (NoSqlClient client : List.of(kv, sdk)) {
            System.out.printf("%n--- Read %d records: sequential vs getBatch [%s, %d runs] ---%n",
                    orders.size(), client.name(), INSERT_RUNS);
            System.out.printf("%-26s  %10s  %12s  %10s%n", "method", "rows/s", "total ms", "calls");
            System.out.println("-".repeat(64));
            compareTotal("sequential (get_single)", orders.size(), INSERT_RUNS, orders.size(), () -> {
                for (Order o : orders)
                    client.get(TABLE, o.customerId(), o.orderId(), ConsistencyLevel.NONE_REQUIRED);
            });
            compareTotal("getBatch (per customer)", orders.size(), INSERT_RUNS, customers, () -> {
                for (List<Order> batch : batches)
                    client.getBatch(TABLE, batch.get(0).customerId(), ConsistencyLevel.NONE_REQUIRED);
            });
        }

        kvStore.close();
        sdkHandle.close();
    }

    private static <T> T retry(String name, Callable<T> action) throws Exception {
        long delay = INITIAL_DELAY;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                T result = action.call();
                if (attempt > 1) System.out.printf("[%s] connected on attempt %d%n", name, attempt);
                return result;
            } catch (Exception e) {
                if (attempt == MAX_ATTEMPTS) throw e;
                System.out.printf("[%s] attempt %d/%d failed: %s — retrying in %d s%n",
                        name, attempt, MAX_ATTEMPTS, e.getMessage(), delay / 1000);
                Thread.sleep(delay);
                delay *= 2; // exponential backoff
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @FunctionalInterface
    interface Op { void run(Order o); }

    @FunctionalInterface
    interface BatchOp { void run(List<Order> batch); }

    private static void measure(NoSqlClient client, String name, List<Order> orders, Op op) {
        int size = orders.size();
        for (int i = 0; i < WARMUP; i++) op.run(orders.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(orders.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-10s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, OPS / (totalMs / 1000), totalMs / OPS);
    }

    private static void measureBatch(NoSqlClient client, String name, List<List<Order>> batches, BatchOp op) {
        int size = batches.size();
        for (int i = 0; i < WARMUP; i++) op.run(batches.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(batches.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-10s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, OPS / (totalMs / 1000), totalMs / OPS);
    }

    private static void compareTotal(String label, int rows, int runs, int calls, Runnable action) {
        action.run(); // warmup
        double totalMs = 0;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            action.run();
            totalMs += (System.nanoTime() - start) / 1_000_000.0;
        }
        double avgMs = totalMs / runs;
        System.out.printf("%-26s  %,10.0f  %12.1f  %,10d%n", label, rows / (avgMs / 1000), avgMs, calls);
    }

    private static Map<String, List<Order>> groupByCustomer(List<Order> orders) {
        Map<String, List<Order>> map = new LinkedHashMap<>();
        for (Order o : orders) {
            map.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }

    private static List<List<Order>> partition(List<Order> orders, int size) {
        List<List<Order>> result = new ArrayList<>();
        for (int i = 0; i < orders.size(); i += size) {
            result.add(orders.subList(i, Math.min(i + size, orders.size())));
        }
        return result;
    }
}
