package com.example.dbperformtest.api.repository.user;

import com.example.dbperformtest.api.dto.UserDto;
import com.example.dbperformtest.api.repository.InMemoryGenericRepository;
import org.springframework.stereotype.Component;

/**
 * In-memory implementation for User.
 *
 * Truyền UserDto::id vào constructor — đó là idExtractor,
 * y chang truyền new UserRowMapper() vào SdkClientImpl.
 *
 * Không cần override method nào — InMemoryGenericRepository lo hết CRUD.
 *
 * Thêm entity mới (ví dụ Order):
 *   @Component("inMemoryOrderRepository")
 *   public class InMemoryOrderRepository extends InMemoryGenericRepository<OrderDto, String>
 *           implements OrderRepository {
 *       public InMemoryOrderRepository() { super(OrderDto::id); }
 *   }
 */
@Component("inMemoryUserRepository")
public class InMemoryUserRepository extends InMemoryGenericRepository<UserDto, String>
        implements UserRepository {

    public InMemoryUserRepository() {
        super(UserDto::id);  // idExtractor: lấy id từ UserDto
    }
}
