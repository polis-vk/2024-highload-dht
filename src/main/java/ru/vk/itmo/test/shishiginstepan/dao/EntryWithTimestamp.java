package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

public record EntryWithTimestamp<D>(D key, D value, Long timestamp) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "}" + " timestamp: " + timestamp;
    }
}
