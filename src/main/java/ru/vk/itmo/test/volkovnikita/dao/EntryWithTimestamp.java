package ru.vk.itmo.test.volkovnikita.dao;

public record EntryWithTimestamp<D>(D key, D value, long timestamp) implements TimestampEntry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
