package com.example.dbperformtest.workload;

import com.example.dbperformtest.model.Order;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads the static dataset ({@code customerId,orderId,amount,payload}, header row first). */
public final class OrderCsvReader {

    static final String HEADER = "customerId,orderId,amount,payload";

    private OrderCsvReader() {
    }

    /** Reads the first {@code limit} rows of the file, or all of them if it has fewer. */
    public static List<Order> read(Path file, int limit) {
        List<Order> orders = new ArrayList<>(limit);
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalArgumentException(file + ": expected header '" + HEADER + "' but got '" + header + "'");
            }
            String previousCustomer = null;
            String line;
            int lineNo = 1;
            while (orders.size() < limit && (line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split(",", -1);
                if (f.length != 4) {
                    throw new IllegalArgumentException(file + ":" + lineNo + ": expected 4 fields, got " + f.length);
                }
                // Rows are grouped by customer: share one String per customer to save memory on large loads.
                String customer = f[0].equals(previousCustomer) ? previousCustomer : f[0];
                previousCustomer = customer;
                orders.add(new Order(customer, Long.parseLong(f[1]), Double.parseDouble(f[2]), f[3]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return orders;
    }
}
