package ru.vk.itmo.test.kovalevigor.dao;

import ru.vk.itmo.Config;
import ru.vk.itmo.Dao;
import ru.vk.itmo.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class DaoImpl implements Dao<MemorySegment, Entry<MemorySegment>> {

    private final SSTableManager ssManager;
    private final ConcurrentNavigableMap<MemorySegment, Entry<MemorySegment>> storage;

    public DaoImpl(final Config config) throws IOException {
        storage = new ConcurrentSkipListMap<>(SSTable.COMPARATOR);
        ssManager = new SSTableManager(config.basePath());
    }

    private static <T> Iterator<T> getValuesIterator(final ConcurrentNavigableMap<?, T> map) {
        return map.values().iterator();
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(final MemorySegment from, final MemorySegment to) {
        Iterator<Entry<MemorySegment>> iterator;
        if (from == null) {
            if (to == null) {
                iterator = getValuesIterator(storage);
            } else {
                iterator = getValuesIterator(storage.headMap(to));
            }
        } else if (to == null) {
            iterator = getValuesIterator(storage.tailMap(from));
        } else {
            iterator = getValuesIterator(storage.subMap(from, to));
        }
        try {
            return new MergeEntryIterator(List.of(
                    new MemEntryPriorityIterator(0, iterator),
                    new MemEntryPriorityIterator(1, ssManager.get(from, to))
            ));
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void upsert(final Entry<MemorySegment> entry) {
        Objects.requireNonNull(entry);
        storage.put(entry.key(), entry);
    }

    @Override
    public Entry<MemorySegment> get(final MemorySegment key) {
        Objects.requireNonNull(key);
        final Entry<MemorySegment> result = storage.get(key);
        if (result != null) {
            if (result.value() == null) {
                return null;
            }
            return result;
        }

        try {
            return ssManager.get(key);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void flush() throws IOException {
        if (!storage.isEmpty()) {
            ssManager.write(storage);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            flush();
        } finally {
            storage.clear();
            ssManager.close();
        }
    }
}
