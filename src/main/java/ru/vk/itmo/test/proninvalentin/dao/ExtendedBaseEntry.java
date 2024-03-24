package ru.vk.itmo.test.proninvalentin.dao;

public record ExtendedBaseEntry<D>(D key, D value, long timestamp) implements ExtendedEntry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "}";
    }
}