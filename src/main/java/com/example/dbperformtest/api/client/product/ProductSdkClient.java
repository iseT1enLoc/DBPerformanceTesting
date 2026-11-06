package com.example.dbperformtest.api.client.product;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.model.Product;
import com.example.dbperformtest.model.ProductKey;
import oracle.nosql.driver.NoSQLHandle;
import org.springframework.stereotype.Component;

@Component
public class ProductSdkClient extends SdkClientImpl<Product, ProductKey> {

    public ProductSdkClient(NoSQLHandle handle) {
        super(handle, new ProductRowMapper(), "products");
    }
}
