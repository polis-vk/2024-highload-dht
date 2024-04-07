package ru.vk.itmo.test.timofeevkirill.dao;

public record BaseTimestampEntry<D>(D key, D value, long timestamp) implements TimestampEntry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "}" + " - " + timestamp;
    }
}
