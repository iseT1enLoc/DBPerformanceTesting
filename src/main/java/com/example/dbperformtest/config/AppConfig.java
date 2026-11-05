package com.example.dbperformtest.config;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.api.client.order.OrderSdkClientImpl;
import com.example.dbperformtest.api.client.user.UserRowMapper;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import com.example.dbperformtest.workload.UserCrudTest;
import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.NoSQLHandleConfig;
import oracle.nosql.driver.NoSQLHandleFactory;
import oracle.nosql.driver.kv.StoreAccessTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration class — the single place that decides how every object
 * in the application is created and wired together.
 *
 * Key Spring concepts shown here:
 *
 *   @Configuration  — marks this class as a source of bean definitions.
 *                     Spring reads it at startup to build its application context.
 *
 *   @Bean           — marks a method whose return value Spring should manage.
 *                     By default every bean is a *singleton*: Spring calls the
 *                     method once and hands the same instance to every caller.
 *
 *   Method calls    — when one @Bean method calls another (e.g. noSqlHandle()),
 *                     Spring intercepts the call and returns the already-created
 *                     singleton instead of running the method again.
 *
 * There is NO component scanning (@ComponentScan) here.  Every bean is wired
 * explicitly, so you can read this file top-to-bottom and see the full object
 * graph without any "magic".
 */
@Configuration
public class AppConfig {

    private static final String ORDERS_TABLE = "orders";
    private static final String USERS_TABLE  = "users";

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    /**
     * The Oracle NoSQL connection handle.
     * Spring creates this bean first because other beans depend on it.
     */
    @Bean
    public NoSQLHandle noSqlHandle() {
        NoSQLHandleConfig config = new NoSQLHandleConfig(
                "http://localhost:8180",
                new StoreAccessTokenProvider());
        return NoSQLHandleFactory.createNoSQLHandle(config);
    }

    // -------------------------------------------------------------------------
    // Clients
    // -------------------------------------------------------------------------

    /**
     * Generic SDK client wired for the User entity.
     * Spring calls noSqlHandle() — which returns the SAME singleton handle.
     */
    @Bean
    public SdkClientImpl<User, UserKey> userSdkClient() {
        return new SdkClientImpl<>(noSqlHandle(), new UserRowMapper(), USERS_TABLE);
    }

    /**
     * Order-specific SDK client.
     * Depends on the same noSqlHandle() singleton.
     */
    @Bean
    public OrderSdkClientImpl orderSdkClient() {
        return new OrderSdkClientImpl(noSqlHandle(), ORDERS_TABLE);
    }

    // -------------------------------------------------------------------------
    // Workloads
    // -------------------------------------------------------------------------

    /**
     * UserCrudTest is itself a bean so Spring can inject its dependency
     * (the user client) automatically — no manual "new" needed in main().
     */
    @Bean
    public UserCrudTest userCrudTest() {
        return new UserCrudTest(noSqlHandle());
    }
}
