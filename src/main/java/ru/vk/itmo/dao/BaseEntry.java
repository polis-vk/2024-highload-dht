package ru.vk.itmo.dao;

public record BaseEntry<D>(D key, D value) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + "}";
    }
}
