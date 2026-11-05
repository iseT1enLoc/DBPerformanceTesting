package com.example.dbperformtest.workload;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.api.client.user.UserRowMapper;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import oracle.nosql.driver.NoSQLHandle;

import com.google.common.collect.Lists;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Smoke-test for User CRUD operations using the generic {@link SdkClientImpl}.
 * Run via {@link com.example.dbperformtest.Main} after the handle is configured.
 */
public class UserCrudTest {

    private static final String TABLE = "users";

    private final SdkClientImpl<User, UserKey> client;

    public UserCrudTest(NoSQLHandle handle) {
        this.client = new SdkClientImpl<>(handle, new UserRowMapper(), TABLE);
    }

    public void run() {
        ensureTable();
        System.out.println("\n=== User CRUD test ===");
        testPutAndGet();
        testPutBulkAndGetBatch();
        testGetListById();
        testWriteBulk();
        testGetListByPrimaryKey();
        testOverwrite();
        testGetMissing();
        System.out.println("=== All user tests passed ===\n");
    }

    public void runGetListById() {
        ensureTable();
        System.out.println("\n=== getListById test ===");
        testGetListById();
        System.out.println("=== getListById test passed ===\n");
    }

    private void ensureTable() {
        client.executeDdl("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (userId STRING, name STRING, email STRING,"
                + " PRIMARY KEY (userId))");
    }

    // --- test cases ---

    private void testPutAndGet() {
        client.put(TABLE, new User("u1", "Alice", "alice@example.com"), DurabilityLevel.COMMIT_SYNC);

        User found = client.get(TABLE, new UserKey("u1"), ConsistencyLevel.ABSOLUTE);
        assertEqual("put/get: userId",  "u1",                found.userId());
        assertEqual("put/get: name",    "Alice",             found.name());
        assertEqual("put/get: email",   "alice@example.com", found.email());
        System.out.println("[OK] put + get");
    }

    private void testPutBulkAndGetBatch() {
        List<User> users = List.of(
                new User("u2", "Bob",   "bob@example.com"),
                new User("u3", "Carol", "carol@example.com"),
                new User("u4", "Dave",  "dave@example.com")
        );
        client.putBulk(TABLE, users, DurabilityLevel.COMMIT_SYNC);

        for (User u : users) {
            User found = client.get(TABLE, new UserKey(u.userId()), ConsistencyLevel.ABSOLUTE);
            assertNotNull("putBulk: " + u.userId(), found);
            assertEqual("putBulk: name " + u.userId(), u.name(), found.name());
        }
        System.out.println("[OK] putBulk + get (3 users)");

        // userId is the only primary key field, so getBatch returns exactly 1 row per call
        List<User> batch = client.getBatch(TABLE, List.of(new UserKey("u2")), ConsistencyLevel.ABSOLUTE);
        assertEqual("getBatch: size", 1, batch.size());
        assertEqual("getBatch: name", "Bob", batch.get(0).name());
        System.out.println("[OK] getBatch");
    }

    private void testGetListById() {
        // seed 10 users so the partition logic has something to split
        List<String> allIds = new ArrayList<>();
        for (int i = 10; i < 20; i++) {
            String id = "ul" + i;
            client.put(TABLE, new User(id, "User" + i, "user" + i + "@example.com"),
                    DurabilityLevel.COMMIT_SYNC);
            allIds.add(id);
        }

        // partition the id list into chunks of 3 and collect results across all partitions
        int partitionSize = 3;
        List<User> allFound = new ArrayList<>();
        for (List<String> partition : Lists.partition(allIds, partitionSize)) {
            allFound.addAll(client.getListById(TABLE, partition, ConsistencyLevel.ABSOLUTE));
        }

        assertEqual("getListById: total results", allIds.size(), allFound.size());
        System.out.println("[OK] getListById (10 users, partitioned by 3)");
    }

    private void testWriteBulk() {
        List<User> users = List.of(
                new User("wb1", "Wendy",  "wendy@example.com"),
                new User("wb2", "Xander", "xander@example.com"),
                new User("wb3", "Yara",   "yara@example.com"),
                new User("wb4", "Zane",   "zane@example.com"),
                new User("wb5", "Amy",    "amy@example.com"),
                new User("wb6", "Ben",    "ben@example.com")
        );

        // partition by shard key then chunk — writeBulk only handles the actual write
        int partitionSize = 2;
        Map<String, List<User>> byShardKey = new LinkedHashMap<>();
        for (User u : users) {
            byShardKey.computeIfAbsent(u.userId(), k -> new ArrayList<>()).add(u);
        }
        for (List<User> shardGroup : byShardKey.values()) {
            for (List<User> chunk : Lists.partition(shardGroup, partitionSize)) {
                client.writeBulk(TABLE, chunk, ConsistencyLevel.ABSOLUTE);
            }
        }

        for (User u : users) {
            User found = client.get(TABLE, new UserKey(u.userId()), ConsistencyLevel.ABSOLUTE);
            assertNotNull("writeBulk: " + u.userId(), found);
            assertEqual("writeBulk: name " + u.userId(), u.name(), found.name());
        }
        System.out.println("[OK] writeBulk (6 users, partitioned by caller)");
    }

    private void testGetListByPrimaryKey() {
        List<UserKey> keys = List.of(
                new UserKey("u1"), new UserKey("u2"),
                new UserKey("u3"), new UserKey("u4"),
                new UserKey("no-such-user")
        );
        List<User> found = client.getListByPrimaryKey(TABLE, keys, ConsistencyLevel.ABSOLUTE);
        // 4 exist, 1 missing — missing silently skipped
        assertEqual("getListByPrimaryKey: size", 4, found.size());
        System.out.println("[OK] getListByPrimaryKey (parallel native get, 4 found / 1 missing)");
    }

    private void testOverwrite() {
        client.put(TABLE, new User("u1", "Alice Updated", "alice2@example.com"),
                DurabilityLevel.COMMIT_SYNC);
        User found = client.get(TABLE, new UserKey("u1"), ConsistencyLevel.ABSOLUTE);
        assertEqual("overwrite: name",  "Alice Updated",      found.name());
        assertEqual("overwrite: email", "alice2@example.com", found.email());
        System.out.println("[OK] overwrite existing row");
    }

    private void testGetMissing() {
        User found = client.get(TABLE, new UserKey("no-such-user"), ConsistencyLevel.ABSOLUTE);
        assertNull("get missing key", found);
        System.out.println("[OK] get missing key returns null");
    }

    // --- helpers ---

    private static void assertEqual(String label, Object expected, Object actual) {
        if (!expected.equals(actual))
            throw new AssertionError(label + ": expected [" + expected + "] but got [" + actual + "]");
    }

    private static void assertNotNull(String label, Object value) {
        if (value == null)
            throw new AssertionError(label + ": expected non-null");
    }

    private static void assertNull(String label, Object value) {
        if (value != null)
            throw new AssertionError(label + ": expected null but got [" + value + "]");
    }
}
