package com.example.dbperformtest.api.repository.product;

import com.example.dbperformtest.api.client.NoSqlClient;
import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Product;
import com.example.dbperformtest.model.ProductKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Single implementation of ProductRepository → @Component without a name.
 * Spring sees exactly 1 bean of type NoSqlClient<Product, ProductKey>
 * and injects it by generic type — no @Qualifier needed.
 */
@Component
public class NoSqlProductRepository implements ProductRepository {

    private static final String TABLE = "products";

    @Resource
    private NoSqlClient<Product, ProductKey> client;

    @PostConstruct
    public void ensureTable() {
        SdkClientImpl<Product, ProductKey> sdk = (SdkClientImpl<Product, ProductKey>) client;
        sdk.executeDdl("DROP TABLE IF EXISTS " + TABLE);
        sdk.executeDdl("CREATE TABLE " + TABLE
                + " (productId STRING, name STRING, price DOUBLE,"
                + " PRIMARY KEY (productId))");
    }

    @Override
    public void save(String id, String name) {
        client.put(TABLE, new Product(id, name, 0.0), DurabilityLevel.COMMIT_SYNC);
    }

    @Override
    public Optional<String> findById(String id) {
        Product p = client.get(TABLE, new ProductKey(id), ConsistencyLevel.ABSOLUTE);
        return Optional.ofNullable(p).map(Product::name);
    }

    @Override
    public List<String> findAll() {
        throw new UnsupportedOperationException("findAll requires a full table scan");
    }

    @Override
    public void deleteById(String id) {
        throw new UnsupportedOperationException("delete not yet in NoSqlClient interface");
    }
}
