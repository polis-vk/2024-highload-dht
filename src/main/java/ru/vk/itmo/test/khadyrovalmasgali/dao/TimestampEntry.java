package ru.vk.itmo.test.khadyrovalmasgali.dao;

import ru.vk.itmo.dao.Entry;

public record TimestampEntry<D>(D key, D value, long timestamp) implements Entry<D> {

    @Override
    public String toString() {
        return "{" + key + ":" + value + "," + timestamp + "}";
    }
}
