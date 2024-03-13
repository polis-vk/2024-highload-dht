package ru.vk.itmo.test.tarazanovmaxim.hash;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashing {
    private final TreeMap<Integer, String> ring = new TreeMap<>();
    private final Hasher hasher = new Hasher();

    private int hashKey(String key) {
        return hasher.digest(key.getBytes(StandardCharsets.UTF_8));
    }

    public String getShardByKey(String key) {
        final Map.Entry<Integer, String> ent = ring.ceilingEntry(hashKey(key));
        if (ent == null) {
            return ring.firstEntry().getValue();
        } else {
            return ent.getValue();
        }
    }

    public void addShard(String newShard, Set<Integer> vnodeHashes) {
        for (final int vnodeHash : vnodeHashes) {
            ring.put(vnodeHash, newShard);
        }
    }

    public void removeShard(final String shard) {
        ring.values().removeIf((String x) -> x.equals(shard));
    }
}
