package ru.vk.itmo.test.pavelemelyanov.dao;

public interface EntryWithTimestamp<D> {
    D key();

    D value();

    long timestamp();
}
