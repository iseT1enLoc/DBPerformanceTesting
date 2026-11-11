package com.example.dbperformtest.ceph.messages;

import java.time.ZonedDateTime;

public record Item(
        String       objectName,
        long         size,
        String       etag,
        ZonedDateTime lastModified,
        boolean      isDir
) {}
