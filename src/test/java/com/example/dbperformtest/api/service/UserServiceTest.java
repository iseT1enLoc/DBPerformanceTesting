package com.example.dbperformtest.api.service;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.repository.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService service;

    @BeforeEach
    void setUp() {
        service = new UserService();
        // UserService uses @Resource injection, so we inject the mock via reflection
        ReflectionTestUtils.setField(service, "userRepository", userRepository);
    }

    // ── getById ──────────────────────────────────────────────────────

    @Test
    void getById_hit_returnsDtoFromRepository() {
        UserDto alice = new UserDto("u1", "Alice", "alice@example.com");
        when(userRepository.findById("u1")).thenReturn(Optional.of(alice));

        Optional<UserDto> result = service.getById("u1");

        assertTrue(result.isPresent());
        assertEquals(alice, result.get());
        verify(userRepository, times(1)).findById("u1");
    }

    @Test
    void getById_miss_returnsEmpty() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertTrue(service.getById("ghost").isEmpty());
    }

    // ── create ───────────────────────────────────────────────────────

    @Test
    void create_delegatesToSave_andReturnsSavedDto() {
        UserDto dto    = new UserDto("u1", "Alice", "alice@example.com");
        UserDto saved  = new UserDto("u1", "Alice", "alice@example.com");
        when(userRepository.save(dto)).thenReturn(saved);

        UserDto result = service.create(dto);

        assertSame(saved, result);
        verify(userRepository, times(1)).save(dto);
    }

    // ── update ───────────────────────────────────────────────────────

    @Test
    void update_savesNewDtoWithPathId_ignoresBodyId() {
        // PUT /users/u1  body = { id: "wrong", name: "Bob", email: "bob@x.com" }
        // service must overwrite id with the path variable "u1"
        UserDto body    = new UserDto("wrong", "Bob", "bob@x.com");
        UserDto expected = new UserDto("u1",   "Bob", "bob@x.com");
        when(userRepository.save(expected)).thenReturn(expected);

        UserDto result = service.update("u1", body);

        assertEquals("u1", result.id());
        verify(userRepository).save(expected);
    }

    // ── delete ───────────────────────────────────────────────────────

    @Test
    void delete_delegatesToDeleteById() {
        service.delete("u1");

        verify(userRepository, times(1)).deleteById("u1");
    }

    // ── listAll ──────────────────────────────────────────────────────

    @Test
    void listAll_returnsRepositoryResult() {
        List<UserDto> all = List.of(
                new UserDto("u1", "Alice", "alice@example.com"),
                new UserDto("u2", "Bob",   "bob@example.com"));
        when(userRepository.findAll()).thenReturn(all);

        assertEquals(all, service.listAll());
    }
}
