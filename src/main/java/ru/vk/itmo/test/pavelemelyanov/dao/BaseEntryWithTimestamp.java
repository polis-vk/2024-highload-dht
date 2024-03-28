package ru.vk.itmo.test.pavelemelyanov.dao;

public record BaseEntryWithTimestamp<D>(D key, D value, long timestamp) implements EntryWithTimestamp<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
