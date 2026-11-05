package com.example.dbperformtest.api.service;

import com.example.dbperformtest.api.repository.product.ProductRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

// Spring sees exactly 1 bean of type ProductRepository → injects it, no @Qualifier needed
@Component
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public void create(String id, String name) {
        productRepository.save(id, name);
    }

    public Optional<String> get(String id) {
        return productRepository.findById(id);
    }

    public List<String> getAll() {
        return productRepository.findAll();
    }

    public void delete(String id) {
        productRepository.deleteById(id);
    }
}
