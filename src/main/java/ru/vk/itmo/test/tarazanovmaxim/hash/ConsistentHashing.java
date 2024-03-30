package ru.vk.itmo.test.tarazanovmaxim.hash;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashing {
    private final Map<Integer, String> ring = new TreeMap<>();
    private final Hasher hasher = new Hasher();

    private int hashKey(String key) {
        return hasher.digest(key.getBytes(StandardCharsets.UTF_8));
    }

    public String getShardByKey(String key) {
        int keyHash = hashKey(key);

        for (Map.Entry<Integer, String> e : ring.entrySet()) {
            if (e.getKey() >= keyHash) {
                return e.getValue();
            }
        }

        return ring.isEmpty() ? null : ring.get(ring.keySet().iterator().next());
    }

    public List<String> getNShardByKey(String key, int n) {
        Set<String> shards = new HashSet<>();
        for (int i = 0; shards.size() < n; ++i) {
            shards.add(getShardByKey(key + i));
        }
        return List.copyOf(shards);
    }

    public void addShard(String newShard, Set<Integer> nodeHashes) {
        for (final int nodeHash : nodeHashes) {
            ring.put(nodeHash, newShard);
        }
    }
}
