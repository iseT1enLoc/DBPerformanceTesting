package com.example.dbperformtest.api.repository.product;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class InMemoryProductRepository implements ProductRepository {

    private final Map<String, String> store = new LinkedHashMap<>();

    @Override
    public void save(String id, String name) {
        store.put(id, name);
    }

    @Override
    public Optional<String> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<String> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public void deleteById(String id) {
        store.remove(id);
    }
}
