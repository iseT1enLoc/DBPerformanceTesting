package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Postgres implementation for User.
 * Does NOT extend InMemoryGenericRepository — it has its own real DB logic.
 */
@Component("postgresUserRepository")
public class PostgresUserRepository implements UserRepository {

    private final String jdbcUrl;

    public PostgresUserRepository(@Value("${db.postgres.url}") String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
        System.out.println("[PostgresUserRepository] configured with: " + jdbcUrl);
    }

    @Override
    public List<UserDto> findAll() {
        throw new UnsupportedOperationException("Wire a real DataSource to implement");
    }

    @Override
    public Optional<UserDto> findById(String id) {
        throw new UnsupportedOperationException("Wire a real DataSource to implement");
    }

    @Override
    public UserDto save(UserDto user) {
        throw new UnsupportedOperationException("Wire a real DataSource to implement");
    }

    @Override
    public void deleteById(String id) {
        throw new UnsupportedOperationException("Wire a real DataSource to implement");
    }
}
