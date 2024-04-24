package ru.vk.itmo.test.reshetnikovaleksei.dao;

public record BaseEntry<D>(D key, D value, long timestamp) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + timestamp + "}";
    }
}
