package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.client.NoSqlClient;
import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component("noSqlUserRepository")
public class NoSqlUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(NoSqlUserRepository.class);
    private static final String TABLE = "users";

    @Resource(name = "userNoSqlClient")
    private NoSqlClient<User, UserKey> client;

    @PostConstruct
    public void ensureTable() {
        log.info("[NoSqlUserRepository] ensuring table '{}'", TABLE);
        ((SdkClientImpl<User, UserKey>) client).executeDdl(
                "CREATE TABLE IF NOT EXISTS " + TABLE
                + " (userId STRING, name STRING, email STRING,"
                + " PRIMARY KEY (userId))");
        log.info("[NoSqlUserRepository] table '{}' ready", TABLE);
    }

    @Override
    public Optional<UserDto> findById(String id) {
        log.info("[Repository] findById({}) → Oracle NoSQL GET", id);
        User user = client.get(TABLE, new UserKey(id), ConsistencyLevel.ABSOLUTE);
        log.info("[Repository] findById({}) → {}", id, user != null ? "hit: " + user : "miss");
        return Optional.ofNullable(user).map(this::toDto);
    }

    @Override
    public UserDto save(UserDto dto) {
        log.info("[Repository] save({}) → Oracle NoSQL PUT", dto);
        client.put(TABLE, toEntity(dto), DurabilityLevel.COMMIT_SYNC);
        log.info("[Repository] save({}) → done", dto.id());
        return dto;
    }

    @Override
    public List<UserDto> findAll() {
        throw new UnsupportedOperationException("findAll requires a table scan — not yet implemented");
    }

    @Override
    public void deleteById(String id) {
        throw new UnsupportedOperationException("delete not yet in NoSqlClient interface");
    }

    private UserDto toDto(User u) {
        return new UserDto(u.userId(), u.name(), u.email());
    }

    private User toEntity(UserDto dto) {
        return new User(dto.id(), dto.name(), dto.email());
    }
}
