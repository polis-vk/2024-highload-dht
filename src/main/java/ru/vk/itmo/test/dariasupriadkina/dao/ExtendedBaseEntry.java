package ru.vk.itmo.test.dariasupriadkina.dao;

public class ExtendedBaseEntry<D> implements ExtendedEntry<D> {

    private final D key;
    private final D value;
    private final long timestampMillis;

    public ExtendedBaseEntry(D key, D value, long timestamp) {
        this.key = key;
        this.value = value;
        this.timestampMillis = timestamp;
    }

    @Override
    public String toString() {
        return "{" + key + ":" + value + ", " + timestampMillis + "}";
    }

    @Override
    public D key() {
        return key;
    }

    @Override
    public D value() {
        return value;
    }

    @Override
    public long timestampMillis() {
        return timestampMillis;
    }
}
