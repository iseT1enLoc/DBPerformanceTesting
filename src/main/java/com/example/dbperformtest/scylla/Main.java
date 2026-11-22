package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.Order;
import com.example.dbperformtest.scylla.model.User;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

public class Main {

    public static void main(String[] args) {
        try (DynamoDbClient db = ScyllaConfig.createClient()) {
            UserRepository  userRepo  = new UserRepository(db);
            OrderRepository orderRepo = new OrderRepository(db);

            userRepo.createTableIfNotExists();
            orderRepo.createTableIfNotExists();

            UserOrderService service = new UserOrderService(userRepo, orderRepo);

            // create users
            service.createUser(new User("u-1", "Alice", "alice@example.com"));
            service.createUser(new User("u-2", "Bob",   "bob@example.com"));

            // place multiple orders per user
            service.placeOrder(new Order("u-1", "order-001", "99.99"));
            service.placeOrder(new Order("u-1", "order-002", "149.50"));
            service.placeOrder(new Order("u-1", "order-003", "49.00"));
            service.placeOrder(new Order("u-2", "order-004", "200.00"));

            // print user with all their orders
            System.out.println("=== Alice ===");
            service.printUserWithOrders("u-1");

            System.out.println("=== Bob ===");
            service.printUserWithOrders("u-2");
        }
    }
}
