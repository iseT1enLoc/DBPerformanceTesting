package com.example.dbperformtest.ceph.args;

import java.util.Objects;

public final class ListObjectsArgs {
    private final String  bucket;
    private final String  prefix;
    private final boolean recursive;
    private final int     maxKeys;

    private ListObjectsArgs(Builder b) {
        this.bucket    = b.bucket;
        this.prefix    = b.prefix;
        this.recursive = b.recursive;
        this.maxKeys   = b.maxKeys;
    }

    public String  bucket()    { return bucket; }
    public String  prefix()    { return prefix; }
    public boolean recursive() { return recursive; }
    public int     maxKeys()   { return maxKeys; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String  bucket;
        private String  prefix    = null;
        private boolean recursive = false;
        private int     maxKeys   = 1000;

        public Builder bucket(String v)    { this.bucket    = v; return this; }
        public Builder prefix(String v)    { this.prefix    = v; return this; }
        public Builder recursive(boolean v){ this.recursive = v; return this; }
        public Builder maxKeys(int v)      { this.maxKeys   = v; return this; }

        public ListObjectsArgs build() {
            Objects.requireNonNull(bucket, "bucket");
            return new ListObjectsArgs(this);
        }
    }
}
