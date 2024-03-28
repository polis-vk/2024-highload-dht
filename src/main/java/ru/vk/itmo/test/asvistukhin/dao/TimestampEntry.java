package ru.vk.itmo.test.asvistukhin.dao;

public record TimestampEntry<T>(T key, T value, long timestamp) implements ITimestampEntry<T> {

    @Override
    public String toString() {
        return "{" + key + ":" + value + " - " + timestamp;
    }

}
