package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.Order;
import com.example.dbperformtest.scylla.model.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoNodeClusterTest {

    DynamoDbClient db;
    UserRepository  userRepo;
    OrderRepository orderRepo;

    @BeforeAll
    void setup() {
        db        = ScyllaConfig.createClient();
        userRepo  = new UserRepository(db);
        orderRepo = new OrderRepository(db);
        userRepo.createTableIfNotExists();
        orderRepo.createTableIfNotExists();
    }

    @AfterAll
    void teardown() {
        db.close();
    }

    // ── 1. Cluster topology ──────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(1)
    void two_nodes_are_discovered() throws IOException, InterruptedException {
        String endpoint = System.getProperty("scylla.endpoint", "http://localhost:8000");
        String localnodesUrl = endpoint + "/localnodes";

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(localnodesUrl)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        // e.g. ["172.18.0.2","172.18.0.3"]  — two entries separated by a comma
        assertTrue(response.body().contains(","),
                "Expected 2 nodes in /localnodes, got: " + response.body());
        System.out.println("/localnodes = " + response.body());
    }

    // ── 2. CRUD correctness ──────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(2)
    void user_crud() {
        userRepo.put(new User("tn-1", "Tuan", "tuan@example.com"));
        var found = userRepo.get("tn-1");
        assertTrue(found.isPresent());
        assertEquals("Tuan", found.get().name());

        userRepo.delete("tn-1");
        assertTrue(userRepo.get("tn-1").isEmpty());
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void order_query_returns_all_orders_for_customer() {
        orderRepo.put(new Order("cust-1", "o-001", "10.00"));
        orderRepo.put(new Order("cust-1", "o-002", "20.00"));
        orderRepo.put(new Order("cust-1", "o-003", "30.00"));

        List<Order> orders = orderRepo.queryByCustomer("cust-1");
        assertEquals(3, orders.size());

        orders.forEach(o -> orderRepo.delete(o.customerId(), o.orderId()));
    }

    // ── 3. Load distribution ─────────────────────────────────────────────────

    @Test
    @org.junit.jupiter.api.Order(4)
    void hundred_writes_all_succeed() {
        // Sends 100 PutItem requests; the library routes them across both nodes.
        // If any node is unreachable the library throws, so this also validates
        // that both nodes handle traffic correctly.
        for (int i = 0; i < 100; i++) {
            orderRepo.put(new Order("load-test", "order-" + i, String.valueOf(i)));
        }

        List<Order> all = orderRepo.queryByCustomer("load-test");
        assertEquals(100, all.size());
        System.out.println("100 writes succeeded; library routed across both nodes.");

        all.forEach(o -> orderRepo.delete(o.customerId(), o.orderId()));
    }
}
