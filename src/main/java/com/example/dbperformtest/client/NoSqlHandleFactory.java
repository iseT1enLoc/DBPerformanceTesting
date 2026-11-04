package com.example.dbperformtest.client;

import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.NoSQLHandleConfig;
import oracle.nosql.driver.NoSQLHandleFactory;

/**
 * Singleton factory for the Oracle NoSQL connection.
 *
 * <pre>{@code
 * // startup
 * NoSqlHandleFactory.configure(new NoSQLHandleConfig(...));
 *
 * // anywhere
 * NoSQLHandle handle = NoSqlHandleFactory.getHandle();
 *
 * // shutdown
 * NoSqlHandleFactory.close();
 * }</pre>
 */
public final class NoSqlHandleFactory {

    private static volatile NoSQLHandle handle;

    private NoSqlHandleFactory() {}

    /** Initializes the shared handle. A second call is ignored if already open. */
    public static synchronized void configure(NoSQLHandleConfig config) {
        if (handle == null) {
            handle = NoSQLHandleFactory.createNoSQLHandle(config);
        }
    }

    /** Returns the shared handle, throwing if {@link #configure} was never called. */
    public static NoSQLHandle getHandle() {
        NoSQLHandle h = handle;
        if (h == null) {
            throw new IllegalStateException(
                    "NoSqlHandleFactory has not been configured — call configure() first");
        }
        return h;
    }

    /** Closes the shared handle and resets the factory. Safe to call multiple times. */
    public static synchronized void close() {
        if (handle != null) {
            handle.close();
            handle = null;
        }
    }
}
