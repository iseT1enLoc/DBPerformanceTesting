package com.example.dbperformtest.ceph.messages;

import java.io.FilterInputStream;
import java.io.InputStream;

/** InputStream with S3 object metadata attached — mirrors MinIO's GetObjectResponse. */
public final class GetObjectResponse extends FilterInputStream {

    private final String bucket;
    private final String object;
    private final String contentType;
    private final long   contentLength;

    public GetObjectResponse(InputStream in, String bucket, String object,
                             String contentType, long contentLength) {
        super(in);
        this.bucket        = bucket;
        this.object        = object;
        this.contentType   = contentType;
        this.contentLength = contentLength;
    }

    public String bucket()        { return bucket; }
    public String object()        { return object; }
    public String contentType()   { return contentType; }
    public long   contentLength() { return contentLength; }
}
