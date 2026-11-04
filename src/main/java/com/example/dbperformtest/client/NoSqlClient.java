package com.example.dbperformtest.client;

import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;

import java.util.List;

/**
 * Generic Oracle NoSQL operations. Implementations must be thread-safe and must fully
 * convert results to {@code T} so deserialization cost is measured equally.
 *
 * @param <T> entity type
 * @param <K> key type
 */
public interface NoSqlClient<T, K> {

    String name();

    void put(String table, T entity, DurabilityLevel durability);

    T get(String table, K key, ConsistencyLevel consistency);

    /**
     * Batch write for rows that share the same shard key.
     * The list is split into chunks of {@code MAX_BATCH_SIZE} and each chunk is sent
     * as a single {@code writeMultiple} request.
     */
    void putBatch(String table, List<T> entities, DurabilityLevel durability);

    /** Individual puts across any shards — no shard-key restriction. */
    void putBulk(String table, List<T> entities, DurabilityLevel durability);

    /**
     * Fetches each key with an individual {@code get} request and returns the found rows.
     * Missing keys are silently skipped (no null in the result list).
     */
    List<T> getBatch(String table, List<K> keys, ConsistencyLevel consistency);
}
