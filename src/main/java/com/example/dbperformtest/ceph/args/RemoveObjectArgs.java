package com.example.dbperformtest.ceph.args;

import java.util.Objects;

public final class RemoveObjectArgs {
    private final String bucket;
    private final String object;
    private RemoveObjectArgs(Builder b) { this.bucket = b.bucket; this.object = b.object; }
    public String bucket() { return bucket; }
    public String object() { return object; }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private String bucket;
        private String object;
        public Builder bucket(String v) { this.bucket = v; return this; }
        public Builder object(String v) { this.object = v; return this; }
        public RemoveObjectArgs build() {
            Objects.requireNonNull(bucket, "bucket");
            Objects.requireNonNull(object, "object");
            return new RemoveObjectArgs(this);
        }
    }
}
