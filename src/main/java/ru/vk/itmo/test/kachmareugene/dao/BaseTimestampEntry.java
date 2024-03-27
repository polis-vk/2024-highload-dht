package ru.vk.itmo.test.kachmareugene.dao;

import java.sql.Timestamp;

public record BaseTimestampEntry<D>(D key, D value, long timestamp) implements EntryWithTimestamp<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ", " + new Timestamp(timestamp) + "}";
    }
}
