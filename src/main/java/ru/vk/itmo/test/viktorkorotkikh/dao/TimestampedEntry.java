package ru.vk.itmo.test.viktorkorotkikh.dao;

import ru.vk.itmo.dao.Entry;

public record TimestampedEntry<D>(D key, D value, long timestamp) implements Entry<D> {
    @Override
    public String toString() {
        return "[" + timestamp + "] {" + key + ":" + value + "}";
    }
}
