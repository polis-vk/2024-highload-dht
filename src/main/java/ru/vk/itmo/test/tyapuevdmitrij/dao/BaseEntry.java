package ru.vk.itmo.test.tyapuevdmitrij.dao;

public record BaseEntry<D>(D key, D value, long timeStamp) implements Entry<D> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + timeStamp + "}";
    }
}
