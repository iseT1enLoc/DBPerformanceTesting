package com.example.dbperformtest.api.repository.product;

import com.example.dbperformtest.api.client.TableClient;
import com.example.dbperformtest.api.client.product.ProductSdkClient;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.Product;
import com.example.dbperformtest.model.ProductKey;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class NoSqlProductRepository implements ProductRepository {

    private static final String TABLE = "products";

    private final TableClient<Product, ProductKey> client;

    public NoSqlProductRepository(ProductSdkClient sdkClient) {
        this.client = new TableClient<>(sdkClient, TABLE);
    }

    @PostConstruct
    public void ensureTable() {
        client.executeDdl("DROP TABLE IF EXISTS " + TABLE);
        client.executeDdl("CREATE TABLE " + TABLE
                + " (productId STRING, name STRING, price DOUBLE,"
                + " PRIMARY KEY (productId))");
    }

    @Override
    public void save(String id, String name) {
        client.put(new Product(id, name, 0.0), DurabilityLevel.COMMIT_SYNC);
    }

    @Override
    public Optional<String> findById(String id) {
        Product p = client.get(new ProductKey(id), ConsistencyLevel.ABSOLUTE);
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
