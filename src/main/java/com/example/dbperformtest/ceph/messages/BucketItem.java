package com.example.dbperformtest.ceph.messages;

import java.time.ZonedDateTime;

public record BucketItem(String name, ZonedDateTime creationDate) {}
