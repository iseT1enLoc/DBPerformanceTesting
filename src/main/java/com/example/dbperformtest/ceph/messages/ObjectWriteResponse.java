package com.example.dbperformtest.ceph.messages;

public record ObjectWriteResponse(String bucket, String object, String etag) {}
