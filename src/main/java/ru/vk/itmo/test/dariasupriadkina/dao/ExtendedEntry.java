package ru.vk.itmo.test.dariasupriadkina.dao;

import ru.vk.itmo.dao.Entry;

public interface ExtendedEntry<D> extends Entry<D> {
    long timestampMillis();
}
