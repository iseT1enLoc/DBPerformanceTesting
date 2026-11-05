package com.example.dbperformtest.api.repository;

import java.util.List;
import java.util.Optional;

/**
 * Generic CRUD contract — mirrors the NoSqlClient<T,K> pattern in this project.
 *
 * @param <T>  entity type  (UserDto, OrderDto, ...)
 * @param <ID> key type     (String, Long, ...)
 *
 * Any entity that needs a repository defines its own interface extending this:
 *   UserRepository  extends GenericRepository<UserDto,  String>
 *   OrderRepository extends GenericRepository<OrderDto, String>
 *
 * The implementations only provide entity-specific logic;
 * all shared CRUD code lives in InMemoryGenericRepository.
 */
public interface GenericRepository<T, ID> {
    List<T>        findAll();
    Optional<T>    findById(ID id);
    T              save(T entity);
    void           deleteById(ID id);
}
