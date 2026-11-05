package com.example.dbperformtest.api.service;

import com.example.dbperformtest.api.repository.product.InMemoryProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring loads only 2 beans: InMemoryProductRepository + ProductService.
 * No @Qualifier, no bean name — Spring resolves ProductRepository by type.
 */
@SpringJUnitConfig(classes = {InMemoryProductRepository.class, ProductService.class})
class ProductServiceTest {

    @Autowired
    ProductService productService;

    @Test
    void create_thenGet_returnsProduct() {
        productService.create("p1", "Laptop");

        Optional<String> found = productService.get("p1");

        assertTrue(found.isPresent());
        assertEquals("Laptop", found.get());
    }

    @Test
    void getAll_returnsAllProducts() {
        productService.create("p1", "Laptop");
        productService.create("p2", "Phone");

        List<String> all = productService.getAll();

        assertEquals(2, all.size());
        assertTrue(all.contains("Laptop"));
        assertTrue(all.contains("Phone"));
    }

    @Test
    void get_missingId_returnsEmpty() {
        assertTrue(productService.get("ghost").isEmpty());
    }

    @Test
    void delete_thenGet_returnsEmpty() {
        productService.create("p1", "Laptop");
        productService.delete("p1");

        assertTrue(productService.get("p1").isEmpty());
    }
}
