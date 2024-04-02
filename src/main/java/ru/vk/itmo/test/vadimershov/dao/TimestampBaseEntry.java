package ru.vk.itmo.test.vadimershov.dao;

public record TimestampBaseEntry<D>(D key, D value, Long timestamp) implements TimestampEntry<D> {
    @Override
    public String toString() {
        return "[" + timestamp + "]{ " + key + " : " + value + " }";
    }
}
