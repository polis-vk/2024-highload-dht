package ru.vk.itmo.test.kovalevigor.dao.entry;

import ru.vk.itmo.dao.Entry;

public interface DaoEntry<D> extends Entry<D> {
    long valueSize();

}
