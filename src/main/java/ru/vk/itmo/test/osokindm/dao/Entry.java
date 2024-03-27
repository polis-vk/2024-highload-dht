package ru.vk.itmo.test.osokindm.dao;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();

}
