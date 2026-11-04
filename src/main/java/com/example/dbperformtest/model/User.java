package com.example.dbperformtest.model;

public class User {

    private String userId;
    private String name;
    private String email;

    public User() {}

    public User(String userId, String name, String email) {
        this.userId = userId;
        this.name   = name;
        this.email  = email;
    }

    public String userId() { return userId; }
    public String name()   { return name; }
    public String email()  { return email; }

    @Override
    public String toString() {
        return "User{userId='" + userId + "', name='" + name + "', email='" + email + "'}";
    }
}
