package ru.vk.itmo.test.solnyshkoksenia.dao;

import ru.vk.itmo.dao.Entry;

public record EntryExtended<Data>(Data key, Data value, Data expiration) implements Entry<Data> {
    @Override
    public String toString() {
        return "{" + key + ":" + value + ":" + expiration + "}";
    }
}
