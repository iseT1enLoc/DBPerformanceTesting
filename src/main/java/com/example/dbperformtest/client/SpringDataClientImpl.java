package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import com.example.dbperformtest.repository.OrderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Spring Data Oracle NoSQL client. Uses {@link OrderRepository} for all operations,
 * routing through the HTTP proxy (port 8180). Durability and consistency are fixed
 * at construction time because the repository does not support per-call settings.
 */
@Component
public class SpringDataClientImpl implements NoSqlClient {

    private final OrderRepository repository;

    @Autowired
    public SpringDataClientImpl(OrderRepository repository) {
        this.repository = repository;
        repository.setDurability("COMMIT_NO_SYNC");
        repository.setConsistency("EVENTUAL");
    }

    @Override
    public String name() { return "spring-data"; }

    @Override
    public void put(String table, Order order, DurabilityLevel durability) {
        repository.save(order);
    }

    @Override
    public Order get(String table, String customerId, long orderId, ConsistencyLevel consistency) {
        return repository.findById(new OrderKey(customerId, orderId)).orElse(null);
    }

    @Override
    public void putBatch(String table, List<Order> orders, DurabilityLevel durability) {
        repository.saveAll(orders);
    }

    @Override
    public List<Order> getBatch(String table, String customerId, ConsistencyLevel consistency) {
        return repository.findById_CustomerId(customerId);
    }

    @Override
    public List<Order> queryByCustomer(String table, String customerId, ConsistencyLevel consistency) {
        return repository.findById_CustomerId(customerId);
    }

    @Override
    public List<Order> queryByAmount(String table, double lo, double hi, ConsistencyLevel consistency) {
        return repository.findByAmountBetween(lo, hi);
    }
}
