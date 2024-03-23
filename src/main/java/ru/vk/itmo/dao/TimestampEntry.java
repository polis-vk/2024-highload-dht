package ru.vk.itmo.dao;

public record TimestampEntry<D>(D key, D value, long timestamp) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
