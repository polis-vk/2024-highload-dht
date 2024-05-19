package ru.vk.itmo.test.vadimershov.dao;

import ru.vk.itmo.dao.Entry;

public interface TimestampEntry<D> extends Entry<D> {
    Long timestamp();

}
