package ru.vk.itmo.test.kachmareugene.dao;

import ru.vk.itmo.dao.Entry;

public interface EntryWithTimestamp<D> extends Entry<D> {
    long timestamp();
}
