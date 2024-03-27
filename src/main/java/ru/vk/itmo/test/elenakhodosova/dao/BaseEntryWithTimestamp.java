package ru.vk.itmo.test.elenakhodosova.dao;

public record BaseEntryWithTimestamp<Data>(Data key, Data value, long timestamp) implements EntryWithTimestamp<Data> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
