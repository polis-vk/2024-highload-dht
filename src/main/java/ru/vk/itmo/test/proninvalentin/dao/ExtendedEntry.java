package ru.vk.itmo.test.proninvalentin.dao;

public interface ExtendedEntry<D> {
    D key();

    D value();

    long timestamp();
}
