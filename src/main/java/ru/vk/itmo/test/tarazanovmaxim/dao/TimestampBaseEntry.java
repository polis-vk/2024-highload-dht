package ru.vk.itmo.test.tarazanovmaxim.dao;

public record TimestampBaseEntry<D>(D key, D value, long timestamp) implements TimestampEntry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
