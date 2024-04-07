package ru.vk.itmo.test.nikitaprokopev.dao;

public record BaseEntry<D>(D key, D value, long timestamp) implements ru.vk.itmo.test.nikitaprokopev.dao.Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "," + timestamp + "}";
    }
}
