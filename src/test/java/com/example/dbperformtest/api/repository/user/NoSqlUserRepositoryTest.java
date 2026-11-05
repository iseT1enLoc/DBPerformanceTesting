package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.client.NoSqlClient;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for NoSqlUserRepository using Mockito.
 *
 * NoSqlClient<User, UserKey> is MOCKED — no real Oracle NoSQL needed.
 * This tests that NoSqlUserRepository:
 *   1. Calls the right client methods (get / put)
 *   2. Maps correctly between UserDto (REST layer) and User (domain model)
 *
 * ReflectionTestUtils.setField injects the mock into the @Resource field
 * without needing a Spring context.
 *
 * @PostConstruct ensureTable() is NOT called here — it creates a table
 * in a real DB and is not relevant to business logic tests.
 */
@ExtendWith(MockitoExtension.class)
class NoSqlUserRepositoryTest {

    @Mock
    private NoSqlClient<User, UserKey> client;   // mock — no real Oracle NoSQL

    private NoSqlUserRepository repository;

    @BeforeEach
    void setUp() {
        repository = new NoSqlUserRepository();
        // inject mock into the @Resource field via reflection
        ReflectionTestUtils.setField(repository, "client", client);
        // @PostConstruct is intentionally NOT called — it needs a real DB
    }

    // ── findById ────────────────────────────────────────────────────

    @Test
    void findById_hit_returnsMappedDto() {
        // given: client returns a User for key "u1"
        User user = new User("u1", "Alice", "alice@example.com");
        when(client.get(eq("users"), eq(new UserKey("u1")), any(ConsistencyLevel.class)))
                .thenReturn(user);

        // when
        Optional<UserDto> result = repository.findById("u1");

        // then: UserDto is correctly mapped from User
        assertTrue(result.isPresent());
        assertEquals("u1",                result.get().id());
        assertEquals("Alice",             result.get().name());
        assertEquals("alice@example.com", result.get().email());

        // verify: client.get was called exactly once with correct args
        verify(client, times(1)).get(eq("users"), eq(new UserKey("u1")), any());
    }

    @Test
    void findById_miss_returnsEmpty() {
        // given: client returns null (key not found)
        when(client.get(any(), any(), any())).thenReturn(null);

        // when
        Optional<UserDto> result = repository.findById("no-such");

        // then
        assertFalse(result.isPresent());
    }

    // ── save ────────────────────────────────────────────────────────

    @Test
    void save_delegatesToClientPut_andReturnsSameDto() {
        UserDto dto = new UserDto("u1", "Alice", "alice@example.com");

        // when
        UserDto result = repository.save(dto);

        // then: client.put was called — verify table name and durability
        verify(client, times(1)).put(
                eq("users"),
                argThat(u -> u.userId().equals("u1")     // User was mapped correctly
                          && u.name().equals("Alice")
                          && u.email().equals("alice@example.com")),
                eq(DurabilityLevel.COMMIT_SYNC));

        // save returns the same dto passed in
        assertSame(dto, result);
    }

    @Test
    void save_mapsFieldsCorrectly_dtoIdBecomesUserId() {
        // UserDto.id() must map to User.userId() — different field names
        UserDto dto = new UserDto("myId", "Name", "email@test.com");

        repository.save(dto);

        verify(client).put(any(), argThat(u -> u.userId().equals("myId")), any());
    }
}
