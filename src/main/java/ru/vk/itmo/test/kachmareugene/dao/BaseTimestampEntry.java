package ru.vk.itmo.test.kachmareugene.dao;

import ru.vk.itmo.dao.Entry;

import java.sql.Timestamp;


public record BaseTimestampEntry<D>(D key, D value, long timestamp) implements EntryWithTimestamp<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ", " + new Timestamp(timestamp) + "}";
    }
}
