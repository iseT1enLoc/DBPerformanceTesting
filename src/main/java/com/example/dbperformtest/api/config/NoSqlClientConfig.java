package com.example.dbperformtest.api.config;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.api.client.TableClient;
import com.example.dbperformtest.api.client.order.OrderRowMapper;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import oracle.nosql.driver.NoSQLHandle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NoSqlClientConfig {

    @Bean("orderNoSqlClient")
    public TableClient<Order, OrderKey> orderNoSqlClient(NoSQLHandle handle) {
        return new TableClient<>(new SdkClientImpl<>(handle, new OrderRowMapper(), "orders"), "orders");
    }

}
