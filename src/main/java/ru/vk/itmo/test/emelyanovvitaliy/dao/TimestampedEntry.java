package ru.vk.itmo.test.emelyanovvitaliy.dao;

import ru.vk.itmo.dao.Entry;

public class TimestampedEntry<D> implements Entry<D> {
    private final D key;
    private final D value;
    private final long timestamp;

    public TimestampedEntry(Entry<D> entry) {
        this(entry.key(), entry.value());
    }

    public TimestampedEntry(D key, D value) {
        this(key, value, System.currentTimeMillis());
    }

    public TimestampedEntry(D key, D value, long timestamp) {
        this.key = key;
        this.value = value;
        this.timestamp = timestamp;
    }

    public long timestamp() {
        return timestamp;
    }

    @Override
    public D key() {
        return key;
    }

    @Override
    public D value() {
        return value;
    }
}
