package com.example.dbperformtest.ceph.args;

import java.util.Objects;

public final class BucketExistsArgs {
    private final String bucket;
    private BucketExistsArgs(Builder b) { this.bucket = b.bucket; }
    public String bucket() { return bucket; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String bucket;
        public Builder bucket(String v) { this.bucket = v; return this; }
        public BucketExistsArgs build() { Objects.requireNonNull(bucket, "bucket"); return new BucketExistsArgs(this); }
    }
}
