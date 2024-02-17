package ru.vk.itmo.test.andreycheshev.dao;

import ru.vk.itmo.dao.Entry;

import java.lang.foreign.MemorySegment;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Memory table.
 *
 * @author incubos
 */
final class MemTable {
    private final NavigableMap<MemorySegment, Entry<MemorySegment>> map =
            new ConcurrentSkipListMap<>(
                    MemorySegmentComparator.INSTANCE);

    boolean isEmpty() {
        return map.isEmpty();
    }

    Iterator<Entry<MemorySegment>> get(
            final MemorySegment from,
            final MemorySegment to) {
        if (from == null && to == null) {
            // All
            return map.values().iterator();
        } else if (from == null) {
            // Head
            return map.headMap(to).values().iterator();
        } else if (to == null) {
            // Tail
            return map.tailMap(from).values().iterator();
        } else {
            // Slice
            return map.subMap(from, to).values().iterator();
        }
    }

    Entry<MemorySegment> get(final MemorySegment key) {
        return map.get(key);
    }

    Entry<MemorySegment> upsert(final Entry<MemorySegment> entry) {
        return map.put(entry.key(), entry);
    }
}
