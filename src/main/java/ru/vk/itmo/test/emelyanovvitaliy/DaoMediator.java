package ru.vk.itmo.test.emelyanovvitaliy;

import one.nio.http.Request;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.emelyanovvitaliy.dao.TimestampedEntry;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;

public abstract class DaoMediator {
    public static final long NEVER_TIMESTAMP = Long.MIN_VALUE;

    protected static MemorySegment keyFor(String id) {
        return MemorySegment.ofArray(id.getBytes(StandardCharsets.UTF_8));
    }

    protected static byte[] valueFor(Entry<MemorySegment> entry) {
        return entry.value().toArray(ValueLayout.JAVA_BYTE);
    }

    abstract void stop();

    abstract boolean isStopped();

    abstract boolean put(Request request);

    // returns null if error,
    // entry with null value and timestamp if the answer is tombstone
    // entry with null value and timestamp equals NEVER_TIMESTAMP if no answer
    // entry if it exists
    abstract TimestampedEntry<MemorySegment> get(Request request);

    abstract boolean delete(Request request);
}
