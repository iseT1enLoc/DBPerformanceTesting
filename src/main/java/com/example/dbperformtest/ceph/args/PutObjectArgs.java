package com.example.dbperformtest.ceph.args;

import java.io.InputStream;
import java.util.Objects;

public final class PutObjectArgs {

    /** Minimum S3 multipart part size (5 MB). */
    public static final long MIN_PART_SIZE = 5 * 1024 * 1024L;

    private final String      bucket;
    private final String      object;
    private final InputStream stream;
    private final long        objectSize;   // -1 = unknown
    private final long        partSize;     // -1 = single PUT, >= MIN_PART_SIZE = auto multipart
    private final String      contentType;

    private PutObjectArgs(Builder b) {
        this.bucket      = b.bucket;
        this.object      = b.object;
        this.stream      = b.stream;
        this.objectSize  = b.objectSize;
        this.partSize    = b.partSize;
        this.contentType = b.contentType;
    }

    public String      bucket()      { return bucket; }
    public String      object()      { return object; }
    public InputStream stream()      { return stream; }
    public long        objectSize()  { return objectSize; }
    public long        partSize()    { return partSize; }
    public String      contentType() { return contentType; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String      bucket;
        private String      object;
        private InputStream stream;
        private long        objectSize  = -1;
        private long        partSize    = -1;
        private String      contentType = "application/octet-stream";

        public Builder bucket(String v)      { this.bucket = v; return this; }
        public Builder object(String v)      { this.object = v; return this; }
        public Builder contentType(String v) { this.contentType = v; return this; }

        /**
         * @param stream     data source
         * @param objectSize total byte count, or -1 when unknown (buffered in memory)
         * @param partSize   bytes per part for multipart; -1 disables multipart
         */
        public Builder stream(InputStream stream, long objectSize, long partSize) {
            this.stream     = stream;
            this.objectSize = objectSize;
            this.partSize   = partSize;
            return this;
        }

        public PutObjectArgs build() {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(stream, "stream");
            if (partSize > 0 && partSize < MIN_PART_SIZE)
                throw new IllegalArgumentException("partSize must be >= " + MIN_PART_SIZE);
            return new PutObjectArgs(this);
        }
    }
}
