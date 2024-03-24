package ru.vk.itmo.test.andreycheshev.dao;

import ru.vk.itmo.dao.Entry;

public record ClusterEntry<T>(T key, T value, long timestamp) implements Entry<T> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "} - " + timestamp;
    }
}
