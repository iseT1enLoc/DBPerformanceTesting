package com.example.dbperformtest.scylla;

import com.example.dbperformtest.scylla.model.Order;
import com.example.dbperformtest.scylla.model.User;

import java.util.List;
import java.util.Optional;

public class UserOrderService {

    private final UserRepository  userRepo;
    private final OrderRepository orderRepo;

    public UserOrderService(UserRepository userRepo, OrderRepository orderRepo) {
        this.userRepo  = userRepo;
        this.orderRepo = orderRepo;
    }

    public void createUser(User user) {
        userRepo.put(user);
    }

    public void placeOrder(Order order) {
        // ensure user exists before placing order
        userRepo.get(order.customerId()).orElseThrow(() ->
                new IllegalArgumentException("User not found: " + order.customerId()));
        orderRepo.put(order);
    }

    public Optional<User> getUser(String userId) {
        return userRepo.get(userId);
    }

    public List<Order> getOrdersByUser(String userId) {
        return orderRepo.queryByCustomer(userId);
    }

    public void printUserWithOrders(String userId) {
        User user = userRepo.get(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        List<Order> orders = orderRepo.queryByCustomer(userId);

        System.out.println("User   : " + user.id() + " | " + user.name() + " | " + user.email());
        System.out.println("Orders : " + orders.size());
        orders.forEach(o ->
                System.out.println("  - orderId=" + o.orderId() + "  amount=" + o.amount()));
    }
}
