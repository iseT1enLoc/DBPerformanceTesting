package com.example.dbperformtest.ceph;

public final class Result<T> {

    private final T         value;
    private final Exception error;

    public Result(T value)         { this.value = value; this.error = null; }
    public Result(Exception error) { this.value = null;  this.error = error; }

    /** Returns the value, or throws the wrapped error. */
    public T get() throws Exception {
        if (error != null) throw error;
        return value;
    }
}
