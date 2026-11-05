package com.example.dbperformtest.api.config;

import oracle.nosql.driver.NoSQLHandle;
import oracle.nosql.driver.NoSQLHandleConfig;
import oracle.nosql.driver.NoSQLHandleFactory;
import oracle.nosql.driver.kv.StoreAccessTokenProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NoSqlConnectionConfig {

    @Bean
    public NoSQLHandle noSqlHandle() {
        NoSQLHandleConfig config = new NoSQLHandleConfig(
                "http://localhost:8180",
                new StoreAccessTokenProvider());
        return NoSQLHandleFactory.createNoSQLHandle(config);
    }
}
