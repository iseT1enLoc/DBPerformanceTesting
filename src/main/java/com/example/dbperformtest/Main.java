package com.example.dbperformtest;

import com.example.dbperformtest.api.client.order.OrderNoSqlClient;
import com.example.dbperformtest.api.client.order.OrderSdkClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import com.example.dbperformtest.workload.OrderCsvReader;
import com.example.dbperformtest.workload.UserCrudTest;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Main {

    private static final String TABLE       = "orders";
    private static final String CSV_FILE    = "data/orders.csv";
    private static final int    RECORDS     = 100_000;
    private static final int    WARMUP      = 200;
    private static final int    OPS         = 1_000;
    private static final int    INSERT_RUNS = 5;
    private static final int    THREADS     = 4;

    public static void main(String[] args) throws Exception {
        /*
         * Boot the Spring container by pointing it at the XML config file.
         * "classpath:" tells Spring to look in src/main/resources/.
         * Spring reads applicationContext.xml, creates every <bean> in
         * dependency order, and stores them as managed singletons.
         *
         * try-with-resources calls context.close() on exit, which triggers
         * Spring's shutdown lifecycle and closes the NoSQLHandle cleanly.
         */
        try (ClassPathXmlApplicationContext context =
                     new ClassPathXmlApplicationContext("applicationContext.xml")) {

            // Pull beans out of the container by type — no "new" in main().
            UserCrudTest       userCrudTest = context.getBean(UserCrudTest.class);
            OrderSdkClientImpl sdk          = context.getBean(OrderSdkClientImpl.class);

            // User CRUD smoke-test
            userCrudTest.run();

            // Create table and secondary index
            sdk.executeDdl("CREATE TABLE IF NOT EXISTS " + TABLE
                    + " (customerId STRING, orderId LONG, amount DOUBLE, payload STRING,"
                    + " PRIMARY KEY (SHARD(customerId), orderId))");
            sdk.executeDdl("CREATE INDEX IF NOT EXISTS idx_amount ON " + TABLE + " (amount)");

            // Load CSV and seed the table
            List<Order> orders = OrderCsvReader.read(Path.of(CSV_FILE), RECORDS);
            System.out.printf("Loaded %d records from %s%n%n", orders.size(), CSV_FILE);
            for (Order order : orders) {
                sdk.put(TABLE, order, DurabilityLevel.COMMIT_NO_SYNC);
            }

            // Group orders by customer (for putBatch — same shard key required)
            List<List<Order>> batches = new ArrayList<>(groupByCustomer(orders).values());

            // Per-operation benchmark
            OrderNoSqlClient client = sdk;
            System.out.printf("%-6s  %-14s  %10s  %12s%n", "client", "operation", "ops/s", "avg ms/op");
            System.out.println("-".repeat(50));
            measure(client, "put_single",   orders,  o -> client.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC));
            measureBatch(client, "put_batch", batches, b -> client.putBatch(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC));
            measureBatch(client, "put_bulk",  batches, b -> client.putBulk(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC));
            measure(client, "get_single",   orders,  o -> client.get(TABLE, OrderKey.of(o.customerId(), o.orderId()), ConsistencyLevel.NONE_REQUIRED));
            measure(client, "get_batch",    orders,  o -> client.getBatch(TABLE, List.of(OrderKey.of(o.customerId(), o.orderId())), ConsistencyLevel.NONE_REQUIRED));
            measure(client, "query_shard",  orders,  o -> client.queryByCustomer(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
            measure(client, "query_amount", orders,  o -> client.queryByAmount(TABLE, o.amount() - 1, o.amount() + 1, ConsistencyLevel.NONE_REQUIRED));

            // Sequential vs queryByCustomer — total time over all RECORDS rows
            int customers = batches.size();
            System.out.printf("%n--- Read %d records: sequential vs getBatch [%d runs] ---%n",
                    orders.size(), INSERT_RUNS);
            System.out.printf("%-26s  %10s  %12s  %10s%n", "method", "rows/s", "total ms", "calls");
            System.out.println("-".repeat(64));
            compareTotal("sequential (get_single)", orders.size(), INSERT_RUNS, orders.size(), () -> {
                for (Order o : orders)
                    client.get(TABLE, OrderKey.of(o.customerId(), o.orderId()), ConsistencyLevel.NONE_REQUIRED);
            });
            compareTotal("queryByCustomer", orders.size(), INSERT_RUNS, customers, () -> {
                for (List<Order> batch : batches)
                    client.queryByCustomer(TABLE, batch.get(0).customerId(), ConsistencyLevel.NONE_REQUIRED);
            });

            // Parallel benchmark — same ops distributed across THREADS workers
            System.out.printf("%n--- Parallel benchmark [threads=%d, ops=%d] ---%n", THREADS, OPS);
            System.out.printf("%-6s  %-14s  %10s  %12s%n", "client", "operation", "ops/s", "avg ms/op");
            System.out.println("-".repeat(50));
            measureParallel(client, "put_single",   orders, THREADS, o -> client.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC));
            measureParallel(client, "put_bulk",     orders, THREADS, o -> client.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC));
            measureParallel(client, "get_single",   orders, THREADS, o -> client.get(TABLE, OrderKey.of(o.customerId(), o.orderId()), ConsistencyLevel.NONE_REQUIRED));
            measureParallel(client, "query_shard",  orders, THREADS, o -> client.queryByCustomer(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
            measureParallel(client, "query_amount", orders, THREADS, o -> client.queryByAmount(TABLE, o.amount() - 1, o.amount() + 1, ConsistencyLevel.NONE_REQUIRED));

        } // context.close() called here — Spring shuts down all beans cleanly
    }

    // -------------------------------------------------------------------------
    // Benchmark helpers
    // -------------------------------------------------------------------------

    @FunctionalInterface interface Op      { void run(Order o); }
    @FunctionalInterface interface BatchOp { void run(List<Order> batch); }

    private static void measure(OrderNoSqlClient client, String name, List<Order> orders, Op op) {
        int size = orders.size();
        for (int i = 0; i < WARMUP; i++) op.run(orders.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(orders.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-6s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, OPS / (totalMs / 1000), totalMs / OPS);
    }

    private static void measureBatch(OrderNoSqlClient client, String name, List<List<Order>> batches, BatchOp op) {
        int size = batches.size();
        for (int i = 0; i < WARMUP; i++) op.run(batches.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(batches.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-6s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, OPS / (totalMs / 1000), totalMs / OPS);
    }

    private static void measureParallel(OrderNoSqlClient client, String name, List<Order> orders, int threads, Op op) throws Exception {
        int size = orders.size();
        for (int i = 0; i < WARMUP; i++) op.run(orders.get(i % size));

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        int opsPerThread = OPS / threads;

        long start = System.nanoTime();
        List<Future<?>> futures = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            final int offset = t * opsPerThread;
            futures.add(pool.submit(() -> {
                for (int i = 0; i < opsPerThread; i++) {
                    op.run(orders.get((offset + i) % size));
                }
            }));
        }
        for (Future<?> f : futures) f.get();
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);

        int totalOps = opsPerThread * threads;
        System.out.printf("%-6s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, totalOps / (totalMs / 1000), totalMs / totalOps);
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
        System.out.printf("%-26s  %,10.0f  %12.1f  %,10d%n",
                label, rows / (avgMs / 1000), avgMs, calls);
    }

    private static Map<String, List<Order>> groupByCustomer(List<Order> orders) {
        Map<String, List<Order>> map = new LinkedHashMap<>();
        for (Order o : orders) {
            map.computeIfAbsent(o.customerId(), k -> new ArrayList<>()).add(o);
        }
        return map;
    }
}
