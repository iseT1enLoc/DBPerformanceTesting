package com.example.dbperformtest.api.controller;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * @WebMvcTest spins up only the web layer (Jackson, filters, DispatcherServlet).
 * No database, no service logic — UserService is mocked.
 *
 * Tests verify:
 *   - HTTP method and URL routing
 *   - Request/response serialization (JSON ↔ DTO)
 *   - HTTP status codes
 */
@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    UserService userService;

    // ── GET /users ───────────────────────────────────────────────────

    @Test
    void listAll_returns200WithJsonArray() throws Exception {
        when(userService.listAll()).thenReturn(List.of(
                new UserDto("u1", "Alice", "alice@example.com"),
                new UserDto("u2", "Bob",   "bob@example.com")));

        mockMvc.perform(get("/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("u1"))
                .andExpect(jsonPath("$[0].name").value("Alice"));
    }

    // ── GET /users/{id} ──────────────────────────────────────────────

    @Test
    void getById_found_returns200WithDto() throws Exception {
        when(userService.getById("u1"))
                .thenReturn(Optional.of(new UserDto("u1", "Alice", "alice@example.com")));

        mockMvc.perform(get("/users/u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("u1"))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.email").value("alice@example.com"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(userService.getById("ghost")).thenReturn(Optional.empty());

        mockMvc.perform(get("/users/ghost"))
                .andExpect(status().isNotFound());
    }

    // ── POST /users ──────────────────────────────────────────────────

    @Test
    void create_returns201WithCreatedDto() throws Exception {
        UserDto body    = new UserDto("u1", "Alice", "alice@example.com");
        UserDto created = new UserDto("u1", "Alice", "alice@example.com");
        when(userService.create(any())).thenReturn(created);

        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("u1"));
    }

    // ── PUT /users/{id} ──────────────────────────────────────────────

    @Test
    void update_returns200WithUpdatedDto() throws Exception {
        UserDto body    = new UserDto("u1", "Alice Updated", "new@example.com");
        UserDto updated = new UserDto("u1", "Alice Updated", "new@example.com");
        when(userService.update(eq("u1"), any())).thenReturn(updated);

        mockMvc.perform(put("/users/u1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Alice Updated"));
    }

    // ── DELETE /users/{id} ───────────────────────────────────────────

    @Test
    void delete_returns204AndCallsService() throws Exception {
        mockMvc.perform(delete("/users/u1"))
                .andExpect(status().isNoContent());

        verify(userService, times(1)).delete("u1");
    }
}
