package com.example.dbperformtest.ceph.messages;

import java.time.ZonedDateTime;

public record StatObjectResponse(
        String        bucket,
        String        object,
        long          size,
        String        contentType,
        String        etag,
        ZonedDateTime lastModified
) {}
