package ru.vk.itmo.test.osipovdaniil.dao;

import ru.vk.itmo.dao.Entry;

public record ReferenceBaseEntry<D>(D key, D value, long timestamp) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "," + timestamp + "}";
    }
}
