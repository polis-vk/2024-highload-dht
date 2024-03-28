package ru.vk.itmo.test.bandurinvladislav.dao;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();
}
