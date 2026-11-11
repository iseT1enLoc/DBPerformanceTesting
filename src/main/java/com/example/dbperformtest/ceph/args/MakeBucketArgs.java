package com.example.dbperformtest.ceph.args;

import java.util.Objects;

public final class MakeBucketArgs {
    private final String bucket;
    private MakeBucketArgs(Builder b) { this.bucket = b.bucket; }
    public String bucket() { return bucket; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String bucket;
        public Builder bucket(String v) { this.bucket = v; return this; }
        public MakeBucketArgs build() { Objects.requireNonNull(bucket, "bucket"); return new MakeBucketArgs(this); }
    }
}
