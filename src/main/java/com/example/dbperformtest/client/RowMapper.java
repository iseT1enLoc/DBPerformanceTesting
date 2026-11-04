package com.example.dbperformtest.client;

import oracle.nosql.driver.values.MapValue;

/**
 * Converts between a domain entity and Oracle NoSQL row/key representations.
 *
 * @param <T> entity type
 * @param <K> key type
 */
public interface RowMapper<T, K> {

    MapValue toRow(T entity);

    MapValue toKey(K key);

    T fromRow(MapValue row);

    /** Name of the shard (partition) key field in the table, used by getBatch. */
    String shardKeyField();
}
