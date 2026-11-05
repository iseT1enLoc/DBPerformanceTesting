package com.example.dbperformtest.api.repository.product;

import java.util.List;
import java.util.Optional;

public interface ProductRepository {
    void save(String id, String name);
    Optional<String> findById(String id);
    List<String> findAll();
    void deleteById(String id);
}
