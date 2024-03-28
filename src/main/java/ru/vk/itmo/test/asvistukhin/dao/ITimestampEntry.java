package ru.vk.itmo.test.asvistukhin.dao;

import ru.vk.itmo.dao.Entry;

public interface ITimestampEntry<T> extends Entry<T> {
    @Override
    T key();

    @Override
    T value();

    long timestamp();
}
