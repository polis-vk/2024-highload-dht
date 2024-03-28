package ru.vk.itmo.test.tuzikovalexandr.dao;

public interface EntryWithTimestamp<D> {
    D key();

    D value();

    long timestamp();
}
