package com.example.dbperformtest.api.dto;

/**
 * Data transferred between the REST layer and the service layer.
 * A plain record — no Spring annotations needed here.
 */
public record UserDto(String id, String name, String email) {}
