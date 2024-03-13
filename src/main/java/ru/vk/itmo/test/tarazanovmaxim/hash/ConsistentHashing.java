package ru.vk.itmo.test.tarazanovmaxim.hash;

import java.nio.charset.StandardCharsets;
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
        Map.Entry<Integer, String> entry = null;

        for (Map.Entry<Integer, String> e : ring.entrySet()) {
            if (e.getKey() >= keyHash) {
                entry = e;
                break;
            }
        }

        if (entry == null) {
            return ring.isEmpty() ? null : ring.get(ring.keySet().iterator().next());
        } else {
            return entry.getValue();
        }
    }

    public void addShard(String newShard, Set<Integer> nodeHashes) {
        for (final int nodeHash : nodeHashes) {
            ring.put(nodeHash, newShard);
        }
    }
}
