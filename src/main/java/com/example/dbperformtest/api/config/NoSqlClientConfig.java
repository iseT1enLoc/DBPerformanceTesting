package com.example.dbperformtest.api.config;

import com.example.dbperformtest.api.client.NoSqlClient;
import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.api.client.order.OrderRowMapper;
import com.example.dbperformtest.api.client.product.ProductRowMapper;
import com.example.dbperformtest.api.client.user.UserRowMapper;
import com.example.dbperformtest.model.Order;
import com.example.dbperformtest.model.OrderKey;
import com.example.dbperformtest.model.Product;
import com.example.dbperformtest.model.ProductKey;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import oracle.nosql.driver.NoSQLHandle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NoSqlClientConfig {

    @Bean("userNoSqlClient")
    public NoSqlClient<User, UserKey> userNoSqlClient(NoSQLHandle handle) {
        return new SdkClientImpl<>(handle, new UserRowMapper(), "users");
    }

    @Bean("orderNoSqlClient")
    public NoSqlClient<Order, OrderKey> orderNoSqlClient(NoSQLHandle handle) {
        return new SdkClientImpl<>(handle, new OrderRowMapper(), "orders");
    }

    @Bean
    public NoSqlClient<Product, ProductKey> productNoSqlClient(NoSQLHandle handle) {
        return new SdkClientImpl<>(handle, new ProductRowMapper(), "products");
    }
}
