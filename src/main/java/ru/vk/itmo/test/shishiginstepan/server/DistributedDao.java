package ru.vk.itmo.test.shishiginstepan.server;

import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class DistributedDao implements Dao<MemorySegment, Entry<MemorySegment>> {
    private static int MULTIPLICATION_FACTOR = 3;
    private final SortedMap<byte[], Dao<MemorySegment, Entry<MemorySegment>>> nodeRing = new ConcurrentSkipListMap<>(
            (b1, b2) -> {
                for (int i = 0; i < Math.min(b1.length, b2.length); i++) {
                    if (b1[i] == b2[i]) continue;
                    else if (b1[i] > b2[i]) {
                        return 1;
                    } else return -1;
                }
                if (b1.length == b2.length) {
                    return 0;
                }
                if (b1.length > b2.length) return 1;
                else return -1;
            }
    );

    private final MessageDigest messageDigest;

    public DistributedDao() {
        try {
            messageDigest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new MissingRequiredHashingAlgorytmException(e);
        }
    }

    private static class MissingRequiredHashingAlgorytmException extends RuntimeException {
        public MissingRequiredHashingAlgorytmException(Exception e) {
            super(e);
        }
    }

    public void addNode(Dao<MemorySegment, Entry<MemorySegment>> daoNode, String token) {
        for (int i = 0; i < MULTIPLICATION_FACTOR; i++) {
            nodeRing.put(messageDigest.digest((token + i).getBytes(StandardCharsets.UTF_8)), daoNode);
        }
    }

    private Dao<MemorySegment, Entry<MemorySegment>> selectNode(byte[] key) {
        Map.Entry<byte[], Dao<MemorySegment, Entry<MemorySegment>>> nodeEntry =
                this.nodeRing.tailMap(messageDigest.digest(key)).firstEntry();
        if (nodeEntry == null) {
            return this.nodeRing.firstEntry().getValue();
        }

        return nodeEntry.getValue();
    }

    @Override
    public Iterator<Entry<MemorySegment>> get(MemorySegment from, MemorySegment to) {
        return null;
    }

    @Override
    public Entry<MemorySegment> get(MemorySegment key) {
        Dao<MemorySegment, Entry<MemorySegment>> dao = selectNode(key.toArray(ValueLayout.JAVA_BYTE));
        return dao.get(key);
    }

    @Override
    public void upsert(Entry<MemorySegment> entry) {
        Dao<MemorySegment, Entry<MemorySegment>> dao = selectNode(entry.key().toArray(ValueLayout.JAVA_BYTE));
        dao.upsert(entry);
    }

    @Override
    public void close() throws IOException {
        for (Dao<MemorySegment, Entry<MemorySegment>> dao : this.nodeRing.values()) {
            dao.close();
        }
    }
}
