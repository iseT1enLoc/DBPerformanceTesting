package com.example.dbperformtest.workload;

import com.example.dbperformtest.client.SdkClientImpl;
import com.example.dbperformtest.client.user.UserRowMapper;
import com.example.dbperformtest.model.ConsistencyLevel;
import com.example.dbperformtest.model.DurabilityLevel;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import oracle.nosql.driver.NoSQLHandle;

import java.util.List;

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
        client.executeDdl("CREATE TABLE IF NOT EXISTS " + TABLE
                + " (userId STRING, name STRING, email STRING,"
                + " PRIMARY KEY (userId))");

        System.out.println("\n=== User CRUD test ===");
        testPutAndGet();
        testPutBulkAndGetBatch();
        testOverwrite();
        testGetMissing();
        System.out.println("=== All user tests passed ===\n");
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
