package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit test — no Spring context, no mocks.
 * InMemoryUserRepository is a plain Java class: just call new.
 *
 * Tests verify the UserRepository contract, not the implementation detail.
 * If we swap to a different impl later, the same tests must still pass.
 */
class InMemoryUserRepositoryTest {

    // test subject typed as the interface — the test doesn't care which impl
    private UserRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUserRepository();   // ← just new, no Spring
    }

    @Test
    void save_thenFindById_returnsUser() {
        UserDto user = new UserDto("u1", "Alice", "alice@example.com");

        repository.save(user);
        Optional<UserDto> found = repository.findById("u1");

        assertTrue(found.isPresent());
        assertEquals("u1",                found.get().id());
        assertEquals("Alice",             found.get().name());
        assertEquals("alice@example.com", found.get().email());
    }

    @Test
    void findById_missingKey_returnsEmpty() {
        Optional<UserDto> found = repository.findById("no-such");

        assertFalse(found.isPresent());
    }

    @Test
    void findAll_returnsAllSavedUsers() {
        repository.save(new UserDto("u1", "Alice", "alice@example.com"));
        repository.save(new UserDto("u2", "Bob",   "bob@example.com"));

        List<UserDto> all = repository.findAll();

        assertEquals(2, all.size());
    }

    @Test
    void save_overwritesExisting() {
        repository.save(new UserDto("u1", "Alice", "alice@example.com"));
        repository.save(new UserDto("u1", "Alice Updated", "alice2@example.com"));

        Optional<UserDto> found = repository.findById("u1");

        assertTrue(found.isPresent());
        assertEquals("Alice Updated",      found.get().name());
        assertEquals("alice2@example.com", found.get().email());
    }

    @Test
    void deleteById_removesUser() {
        repository.save(new UserDto("u1", "Alice", "alice@example.com"));
        repository.deleteById("u1");

        assertFalse(repository.findById("u1").isPresent());
    }

    @Test
    void deleteById_nonExistent_doesNotThrow() {
        assertDoesNotThrow(() -> repository.deleteById("ghost"));
    }
}
