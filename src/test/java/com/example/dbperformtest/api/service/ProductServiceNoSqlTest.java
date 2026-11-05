package com.example.dbperformtest.api.service;

import com.example.dbperformtest.api.config.NoSqlClientConfig;
import com.example.dbperformtest.api.config.NoSqlConnectionConfig;
import com.example.dbperformtest.api.repository.product.NoSqlProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring wires:
 *   NoSqlConnectionConfig  → NoSQLHandle bean
 *   NoSqlClientConfig      → NoSqlClient<User,UserKey> bean  (resolved by generic type, no name)
 *   NoSqlProductRepository → @Autowired NoSqlClient<User,UserKey>  (1 impl → no @Qualifier)
 *   ProductService         → @Autowired ProductRepository           (1 impl → no @Qualifier)
 */
@SpringJUnitConfig(classes = {
        NoSqlConnectionConfig.class,
        NoSqlClientConfig.class,
        NoSqlProductRepository.class,
        ProductService.class
})
class ProductServiceNoSqlTest {

    @Autowired
    ProductService productService;

    @Test
    void create_thenGet_returnsProductFromDb() {
        productService.create("prod-1", "Laptop");

        Optional<String> found = productService.get("prod-1");

        assertTrue(found.isPresent());
        assertEquals("Laptop", found.get());
    }

    @Test
    void get_missingId_returnsEmpty() {
        Optional<String> found = productService.get("no-such-product");

        assertFalse(found.isPresent());
    }
}
