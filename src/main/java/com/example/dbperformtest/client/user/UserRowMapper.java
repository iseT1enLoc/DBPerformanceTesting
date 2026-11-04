package com.example.dbperformtest.client.user;

import com.example.dbperformtest.client.RowMapper;
import com.example.dbperformtest.model.User;
import com.example.dbperformtest.model.UserKey;
import oracle.nosql.driver.values.MapValue;

public class UserRowMapper implements RowMapper<User, UserKey> {

    @Override
    public MapValue toRow(User u) {
        return new MapValue()
                .put("userId", u.userId())
                .put("name",   u.name())
                .put("email",  u.email());
    }

    @Override
    public MapValue toKey(UserKey key) {
        return new MapValue().put("userId", key.userId());
    }

    @Override
    public User fromRow(MapValue v) {
        return new User(
                v.getString("userId"),
                v.getString("name"),
                v.getString("email"));
    }

    @Override
    public String shardKeyField() { return "userId"; }

    @Override
    public String shardKeyValue(User entity) { return entity.userId(); }

    @Override
    public String[] columns() { return new String[]{"userId", "name", "email"}; }
}
