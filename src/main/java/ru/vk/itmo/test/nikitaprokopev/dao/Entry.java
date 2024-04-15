package ru.vk.itmo.test.nikitaprokopev.dao;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();
}
