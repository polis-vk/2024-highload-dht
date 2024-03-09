package ru.vk.itmo.test.ryabovvadim.dao.memtable;

import ru.vk.itmo.dao.Entry;

import java.util.concurrent.atomic.AtomicReference;

public class AtomicEntryWithLock<T> implements Entry<T> {
    private final T key;
    private final AtomicReference<T> value;

    public AtomicEntryWithLock(Entry<T> entry) {
        this.key = entry.key();
        this.value = new AtomicReference<>(entry.value());
    }

    @Override
    public T key() {
        return key;
    }

    @Override
    public T value() {
        return value.get();
    }

    public T getAndSet(T value) {
        return this.value.getAndSet(value);
    }
}
