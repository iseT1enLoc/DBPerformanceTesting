package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.client.user.UserSdkClient;
import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit test for NoSqlUserRepository.
 *
 * UserSdkClient is MOCKED — no real Oracle NoSQL needed.
 * TableClient is a plain wrapper constructed inside the repository;
 * all calls flow through it down to the mocked sdkClient, so verify()
 * targets sdkClient and must include the table name "users".
 *
 * @PostConstruct ensureTable() is NOT called — it DDLs a real database.
 */
@ExtendWith(MockitoExtension.class)
class NoSqlUserRepositoryTest {

    @Mock
    private UserSdkClient sdkClient;   // mock — no real Oracle NoSQL

    private NoSqlUserRepository repository;

    @BeforeEach
    void setUp() {
        // Constructor wraps sdkClient in TableClient internally
        repository = new NoSqlUserRepository(sdkClient);
        // @PostConstruct is intentionally NOT called — it needs a real DB
    }

    // ── findById ────────────────────────────────────────────────────

    @Test
    void findById_hit_returnsMappedDto() {
        User user = new User("u1", "Alice", "alice@example.com");
        when(sdkClient.get(eq("users"), eq(new UserKey("u1")), any(ConsistencyLevel.class)))
                .thenReturn(user);

        Optional<UserDto> result = repository.findById("u1");

        assertTrue(result.isPresent());
        assertEquals("u1",                result.get().id());
        assertEquals("Alice",             result.get().name());
        assertEquals("alice@example.com", result.get().email());

        verify(sdkClient, times(1)).get(eq("users"), eq(new UserKey("u1")), any());
    }

    @Test
    void findById_miss_returnsEmpty() {
        when(sdkClient.get(any(), any(), any())).thenReturn(null);

        assertFalse(repository.findById("no-such").isPresent());
    }

    // ── save ────────────────────────────────────────────────────────

    @Test
    void save_delegatesToClientPut_andReturnsSameDto() {
        UserDto dto = new UserDto("u1", "Alice", "alice@example.com");

        UserDto result = repository.save(dto);

        verify(sdkClient, times(1)).put(
                eq("users"),
                argThat(u -> u.userId().equals("u1")
                          && u.name().equals("Alice")
                          && u.email().equals("alice@example.com")),
                eq(DurabilityLevel.COMMIT_SYNC));

        assertSame(dto, result);
    }

    @Test
    void save_mapsFieldsCorrectly_dtoIdBecomesUserId() {
        UserDto dto = new UserDto("myId", "Name", "email@test.com");

        repository.save(dto);

        verify(sdkClient).put(any(), argThat(u -> u.userId().equals("myId")), any());
    }
}
