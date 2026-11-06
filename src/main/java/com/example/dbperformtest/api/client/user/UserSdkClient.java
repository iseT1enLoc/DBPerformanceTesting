package com.example.dbperformtest.api.client.user;

import com.example.dbperformtest.api.client.SdkClientImpl;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import oracle.nosql.driver.NoSQLHandle;
import org.springframework.stereotype.Component;

@Component
public class UserSdkClient extends SdkClientImpl<User, UserKey> {

    public UserSdkClient(NoSQLHandle handle) {
        super(handle, new UserRowMapper(), "users");
    }
}
