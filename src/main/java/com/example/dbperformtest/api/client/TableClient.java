package com.example.dbperformtest.api.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;

import java.util.List;

/**
 * Binds a {@link SdkClientImpl} to a single table so callers never repeat the table name.
 *
 * @param <T> entity type
 * @param <K> key type
 */
public class TableClient<T, K> {

    private final SdkClientImpl<T, K> delegate;
    private final String table;

    public TableClient(SdkClientImpl<T, K> delegate, String table) {
        this.delegate = delegate;
        this.table    = table;
    }

    public String table() { return table; }

    public String name() { return delegate.name(); }

    public void put(T entity, DurabilityLevel durability) {
        delegate.put(table, entity, durability);
    }

    public T get(K key, ConsistencyLevel consistency) {
        return delegate.get(table, key, consistency);
    }

    public void putBatch(List<T> entities, DurabilityLevel durability) {
        delegate.putBatch(table, entities, durability);
    }

    public void putBulk(List<T> entities, DurabilityLevel durability) {
        delegate.putBulk(table, entities, durability);
    }

    public List<T> getBatch(List<K> keys, ConsistencyLevel consistency) {
        return delegate.getBatch(table, keys, consistency);
    }

    public List<T> getListById(List<String> keys, ConsistencyLevel consistency) {
        return delegate.getListById(table, keys, consistency);
    }

    public List<T> getListByPrimaryKey(List<K> keys, ConsistencyLevel consistency) {
        return delegate.getListByPrimaryKey(table, keys, consistency);
    }

    public void writeBulk(List<T> entities, ConsistencyLevel consistency) {
        delegate.writeBulk(table, entities, consistency);
    }

    public void executeDdl(String ddl) {
        delegate.executeDdl(ddl);
    }
}
