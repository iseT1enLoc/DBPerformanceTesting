package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.repository.GenericRepository;

/**
 * User-specific repository contract.
 * Extends GenericRepository with concrete types so the rest of the
 * code never has to write the type parameters again.
 *
 * Pattern mirrors OrderNoSqlClient extends NoSqlClient<Order, OrderKey>.
 */
public interface UserRepository extends GenericRepository<UserDto, String> {
    // add User-specific query methods here when needed
    // e.g. Optional<UserDto> findByEmail(String email);


}
