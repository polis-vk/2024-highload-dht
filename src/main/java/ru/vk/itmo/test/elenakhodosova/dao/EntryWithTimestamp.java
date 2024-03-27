package ru.vk.itmo.test.elenakhodosova.dao;

import ru.vk.itmo.dao.Entry;

public interface EntryWithTimestamp<D> extends Entry<D> {
    long timestamp();
}
