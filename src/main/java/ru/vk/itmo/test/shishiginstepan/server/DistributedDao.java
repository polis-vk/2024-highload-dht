package ru.vk.itmo.test.shishiginstepan.server;

import one.nio.util.Hash;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class DistributedDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private static final int MULTIPLICATION_FACTOR = 128;
    private final SortedMap<Integer, Dao<MemorySegment, Entry<MemorySegment>>> nodeRing = new ConcurrentSkipListMap<>();

    public void addNode(Dao<MemorySegment, Entry<MemorySegment>> daoNode, String token) {
        for (int i = 0; i < MULTIPLICATION_FACTOR; i++) {
            nodeRing.put(Hash.murmur3((token + i)), daoNode);
        }
    }

    private Dao<MemorySegment, Entry<MemorySegment>> selectNode(String key) {
        Map.Entry<Integer, Dao<MemorySegment, Entry<MemorySegment>>> nodeEntry =
                this.nodeRing.tailMap(Hash.murmur3(key)).firstEntry();
        if (nodeEntry == null) {
            return this.nodeRing.lastEntry().getValue();
        }

        return nodeEntry.getValue();
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return null;
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Dao<MemorySegment, Entry<MemorySegment>> dao = selectNode(
                new String(key.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
        );
        return dao.get(key);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        Dao<MemorySegment, Entry<MemorySegment>> dao = selectNode(
                new String(entry.key().toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8)
        );
        dao.upsert(entry);
    }

    @Override
    public void close() throws IOException {
        for (Dao<MemorySegment, Entry<MemorySegment>> dao : this.nodeRing.values()) {
            dao.close();
        }
    }
}
