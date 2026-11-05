package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * MySQL implementation for User.
 */
@Component("mySqlUserRepository")
public class MySqlUserRepository implements UserRepository {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public MySqlUserRepository(
            @Value("${db.mysql.url}")      String jdbcUrl,
            @Value("${db.mysql.username}") String username,
            @Value("${db.mysql.password}") String password) {
        this.jdbcUrl  = jdbcUrl;
        this.username = username;
        this.password = password;
        System.out.println("[MySqlUserRepository] configured with: " + jdbcUrl + " / " + username);
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
