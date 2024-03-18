package ru.vk.itmo.test.shishiginstepan.dao;

import ru.vk.itmo.dao.Entry;

import java.util.Iterator;

public interface SSTable<D, E extends Entry<D>> {
    E get(D key);

    Iterator<E> scan(D keyFrom, D keyTo);
}
