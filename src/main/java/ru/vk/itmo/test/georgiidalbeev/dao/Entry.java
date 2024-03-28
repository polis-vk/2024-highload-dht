package ru.vk.itmo.test.georgiidalbeev.dao;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();
}
