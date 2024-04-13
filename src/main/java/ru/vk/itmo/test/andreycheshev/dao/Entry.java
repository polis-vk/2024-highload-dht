package ru.vk.itmo.test.andreycheshev.dao;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();
}
