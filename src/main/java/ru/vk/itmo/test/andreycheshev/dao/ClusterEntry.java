package ru.vk.itmo.test.andreycheshev.dao;

public record ClusterEntry<T>(T key, T value, long timestamp) implements Entry<T> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "} - " + timestamp;
    }
}
