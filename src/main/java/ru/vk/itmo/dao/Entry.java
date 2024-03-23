package ru.vk.itmo.dao;

public interface Entry<D> {
    D key();

    D value();

    default long timestamp() {
        return 0L;
    }
}
