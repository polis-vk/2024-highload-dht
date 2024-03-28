package ru.vk.itmo.test.timofeevkirill.dao;

import ru.vk.itmo.dao.Entry;

public interface TimestampEntry<D> extends Entry<D> {
    @Override D key();

    @Override D value();

    long timestamp();
}
