package ru.vk.itmo.test.tarazanovmaxim.hash;

import one.nio.util.Hash;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> ring = new TreeMap<>();

    private int hashKey(String key) {
        return Hash.murmur3(key);
    }

    public String getShardByKey(String key) {
        final Map.Entry<Integer, String> ent = ring.ceilingEntry(hashKey(key));
        return ent == null ? ring.firstEntry().getValue() : ent.getValue();
    }

    public List<String> getNShardByKey(final String key, final int n) {
        Set<String> shards = new HashSet<>();
        shards.add(getShardByKey(key));
        for (int i = 0; shards.size() < n; ++i) {
            shards.add(getShardByKey(key + i));
        }

        return List.copyOf(ring.values());
    }

    public void addShard(String newShard, Set<Integer> nodeHashes) {
        for (final int nodeHash : nodeHashes) {
            ring.putIfAbsent(nodeHash, newShard);
        }
    }
}
