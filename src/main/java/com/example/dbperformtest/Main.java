package com.example.dbperformtest;

import com.example.dbperformtest.client.NoSqlClient;
import com.example.dbperformtest.client.SpringDataClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.workload.OrderCsvReader;
import com.oracle.nosql.spring.data.core.NosqlTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class Main implements CommandLineRunner {

    private static final String TABLE     = "orders";
    private static final String CSV_FILE  = "data/orders.csv";
    private static final int    RECORDS    = 100_000;
    private static final int    WARMUP     = 200;
    private static final int    OPS        = 1_000;
    private static final int    INSERT_RUNS = 5;

    @Autowired private SpringDataClientImpl springData;
    @Autowired private NosqlTemplate nosqlTemplate;

    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        // Create secondary index (Spring Data only auto-creates the table, not indexes)
        nosqlTemplate.runTableRequest(
                "CREATE INDEX IF NOT EXISTS idx_amount ON " + TABLE + " (amount)");

        // Load CSV and seed the table
        List<Order> orders = OrderCsvReader.read(Path.of(CSV_FILE), RECORDS);
        System.out.printf("Loaded %d records from %s%n%n", orders.size(), CSV_FILE);
        for (Order order : orders) {
            springData.put(TABLE, order, DurabilityLevel.COMMIT_NO_SYNC);
        }

        // Group orders by customer (for putBatch)
        List<List<Order>> batches = new ArrayList<>(groupByCustomer(orders).values());

        // Per-operation benchmark
        NoSqlClient client = springData;
        System.out.printf("%-12s  %-14s  %10s  %12s%n", "client", "operation", "ops/s", "avg ms/op");
        System.out.println("-".repeat(54));
        measure(client, "put_single", orders,
                o -> client.put(TABLE, o, DurabilityLevel.COMMIT_NO_SYNC));
        measureBatch(client, "put_batch", batches,
                b -> client.putBatch(TABLE, b, DurabilityLevel.COMMIT_NO_SYNC));
        measure(client, "get_single", orders,
                o -> client.get(TABLE, o.customerId(), o.orderId(), ConsistencyLevel.NONE_REQUIRED));
        measure(client, "get_batch", orders,
                o -> client.getBatch(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
        measure(client, "query_shard", orders,
                o -> client.queryByCustomer(TABLE, o.customerId(), ConsistencyLevel.NONE_REQUIRED));
        measure(client, "query_amount", orders,
                o -> client.queryByAmount(TABLE, o.amount() - 1, o.amount() + 1, ConsistencyLevel.NONE_REQUIRED));

        // Sequential vs getBatch total-time comparison
        int customers = batches.size();
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

    @FunctionalInterface interface Op      { void run(Order o); }
    @FunctionalInterface interface BatchOp { void run(List<Order> batch); }

    private static void measure(NoSqlClient client, String name, List<Order> orders, Op op) {
        int size = orders.size();
        for (int i = 0; i < WARMUP; i++) op.run(orders.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(orders.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-12s  %-14s  %,10.0f  %12.3f%n",
                client.name(), name, OPS / (totalMs / 1000), totalMs / OPS);
    }

    private static void measureBatch(NoSqlClient client, String name, List<List<Order>> batches, BatchOp op) {
        int size = batches.size();
        for (int i = 0; i < WARMUP; i++) op.run(batches.get(i % size));
        long start = System.nanoTime();
        for (int i = 0; i < OPS; i++) op.run(batches.get(i % size));
        double totalMs = (System.nanoTime() - start) / 1_000_000.0;
        System.out.printf("%-12s  %-14s  %,10.0f  %12.3f%n",
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
}
