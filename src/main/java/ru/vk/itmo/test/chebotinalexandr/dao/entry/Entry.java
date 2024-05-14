package ru.vk.itmo.test.chebotinalexandr.dao.entry;

public interface Entry<D> {
    D key();

    D value();

    long timestamp();
}
