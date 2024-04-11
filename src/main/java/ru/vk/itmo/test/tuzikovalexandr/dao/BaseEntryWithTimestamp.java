package ru.vk.itmo.test.tuzikovalexandr.dao;

public record BaseEntryWithTimestamp<D>(D key, D value, long timestamp) implements EntryWithTimestamp<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
