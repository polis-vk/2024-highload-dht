package ru.vk.itmo.test.smirnovdmitrii.dao;

import ru.vk.itmo.dao.Entry;

public record TimeEntry<D>(
        long millis,
        D key,
        D value
) implements Entry<D> {
}
