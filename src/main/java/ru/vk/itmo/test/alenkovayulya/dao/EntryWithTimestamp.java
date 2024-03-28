package ru.vk.itmo.test.alenkovayulya.dao;

public interface EntryWithTimestamp<D> {
    D key();

    D value();

    long timestamp();
}
