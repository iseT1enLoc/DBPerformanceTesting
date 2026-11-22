package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.Order;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.TestInstance;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderRepositoryTest {

    static DynamoDbClient db;
    static OrderRepository repo;

    @BeforeAll
    static void setup() {
        db   = ScyllaConfig.createClient();
        repo = new OrderRepository(db);
        repo.createTableIfNotExists();
    }

    @AfterAll
    static void teardown() {
        db.close();
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void put_and_get() {
        repo.put(new Order("test-customer", "order-1", "100.00"));
        var item = repo.get("test-customer", "order-1");
        assertTrue(item.isPresent());
        assertEquals("100.00", item.get().amount());
    }

    @Test
    @org.junit.jupiter.api.Order(2)
    void query_returns_all_customer_orders() {
        repo.put(new Order("test-customer", "order-2", "200.00"));
        List<Order> orders = repo.queryByCustomer("test-customer");
        assertTrue(orders.size() >= 2);
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void delete_removes_item() {
        repo.delete("test-customer", "order-1");
        assertTrue(repo.get("test-customer", "order-1").isEmpty());
    }
}
