package com.example.dbperformtest.api.repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Generic in-memory CRUD implementation — mirrors SdkClientImpl<T, K>.
 *
 * SdkClientImpl nhận RowMapper<T,K> để biết cách convert entity ↔ DB row.
 * Class này nhận Function<T, ID> để biết cách lấy ID từ entity.
 * Ngoài ra không cần biết T là gì — mọi CRUD đều chạy được.
 *
 *   SdkClientImpl pattern:          Repository pattern:
 *   ─────────────────────           ────────────────────
 *   NoSqlClient<T, K>          ↔    GenericRepository<T, ID>
 *   SdkClientImpl<T, K>        ↔    InMemoryGenericRepository<T, ID>
 *   RowMapper<T, K>            ↔    Function<T, ID>   (idExtractor)
 *   new SdkClientImpl(         ↔    new InMemoryGenericRepository<>(
 *     handle, mapper)                 entity -> entity.id())
 *
 * Class này là concrete (không abstract) — dùng thẳng hoặc extend thêm
 * method domain-specific, y chang OrderSdkClientImpl extends SdkClientImpl.
 *
 * @param <T>  entity type  (UserDto, OrderDto, ...)
 * @param <ID> key type     (String, Long, ...)
 */
public class InMemoryGenericRepository<T, ID> implements GenericRepository<T, ID> {

    private final Map<ID, T> store = new LinkedHashMap<>();

    /**
     * Strategy: biết cách lấy ID từ một entity.
     * Truyền vào bằng method reference: UserDto::id, OrderDto::orderId, ...
     * Tương đương RowMapper trong SdkClientImpl.
     */
    private final Function<T, ID> idExtractor;

    public InMemoryGenericRepository(Function<T, ID> idExtractor) {
        this.idExtractor = idExtractor;
    }

    @Override
    public List<T> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public T save(T entity) {
        store.put(idExtractor.apply(entity), entity);
        return entity;
    }

    @Override
    public void deleteById(ID id) {
        store.remove(id);
    }
}
