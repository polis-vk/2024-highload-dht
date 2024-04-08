package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public abstract class DaoMediator {
    public static final long NEVER_TIMESTAMP = Long.MIN_VALUE;

    protected static MemorySegment keyFor(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    abstract void stop();

    abstract boolean isStopped();

    abstract CompletableFuture<Boolean> put(Request request);

    // returns null if error,
    // entry with null value and timestamp if the answer is tombstone
    // entry with null value and timestamp equals NEVER_TIMESTAMP if no answer
    // entry if it exists
    abstract CompletableFuture<TimestampedEntry<MemorySegment>> get(Request request);

    abstract CompletableFuture<Boolean> delete(Request request);
}
