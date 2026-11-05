package com.example.dbperformtest.api.service;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.repository.user.UserRepository;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * @Resource(name = "...") nói thẳng ra bean nào được inject.
 * Đổi name= để switch database.
 */
@Component
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    @Resource(name = "noSqlUserRepository")
    private UserRepository userRepository;

    public List<UserDto> listAll() {
        log.info("[Service] listAll → delegate to {}", userRepository.getClass().getSimpleName());
        return userRepository.findAll();
    }

    public Optional<UserDto> getById(String id) {
        log.info("[Service] getById({}) → delegate to {}", id, userRepository.getClass().getSimpleName());
        Optional<UserDto> result = userRepository.findById(id);
        log.info("[Service] getById({}) → {}", id, result.isPresent() ? "found" : "empty");
        return result;
    }

    public UserDto create(UserDto user) {
        log.info("[Service] create({}) → delegate to {}", user, userRepository.getClass().getSimpleName());
        UserDto result = userRepository.save(user);
        log.info("[Service] create done → {}", result);
        return result;
    }

    public UserDto update(String id, UserDto user) {
        log.info("[Service] update({}) → delegate to {}", id, userRepository.getClass().getSimpleName());
        UserDto result = userRepository.save(new UserDto(id, user.name(), user.email()));
        log.info("[Service] update done → {}", result);
        return result;
    }

    public void delete(String id) {
        log.info("[Service] delete({}) → delegate to {}", id, userRepository.getClass().getSimpleName());
        userRepository.deleteById(id);
        log.info("[Service] delete done: {}", id);
    }
}
