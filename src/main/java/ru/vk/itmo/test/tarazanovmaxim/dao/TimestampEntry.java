package ru.vk.itmo.test.tarazanovmaxim.dao;

import ru.vk.itmo.dao.Entry;

public interface TimestampEntry<D> extends Entry<D> {
    long timestamp();
}
