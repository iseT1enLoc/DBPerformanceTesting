package com.example.dbperformtest.api.controller;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.service.UserService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Resource
    private UserService userService;

    @GetMapping
    public List<UserDto> listAll() {
        log.info("[Controller] GET /users");
        List<UserDto> result = userService.listAll();
        log.info("[Controller] returning {} users", result.size());
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getById(@PathVariable String id) {
        log.info("[Controller] GET /users/{}", id);
        return userService.getById(id)
                .map(u -> { log.info("[Controller] found: {}", u); return ResponseEntity.ok(u); })
                .orElseGet(() -> { log.info("[Controller] not found: {}", id); return ResponseEntity.notFound().build(); });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@RequestBody UserDto user) {
        log.info("[Controller] POST /users body={}", user);
        UserDto result = userService.create(user);
        log.info("[Controller] created: {}", result);
        return result;
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable String id, @RequestBody UserDto user) {
        log.info("[Controller] PUT /users/{} body={}", id, user);
        UserDto result = userService.update(id, user);
        log.info("[Controller] updated: {}", result);
        return result;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        log.info("[Controller] DELETE /users/{}", id);
        userService.delete(id);
        log.info("[Controller] deleted: {}", id);
    }
}
