package ru.vk.itmo.test.pavelemelyanov;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public class ConsistentHashing {
    private final Map<Integer, String> virtualNodeMapping = new TreeMap<>();
    private final HashService hashService = new HashService();

    public String getShardByKey(String key) {
        int keyHash = hashKey(key);
        Map.Entry<Integer, String> entry = null;

        for (Map.Entry<Integer, String> virtualNode : virtualNodeMapping.entrySet()) {
            if (virtualNode.getKey() >= keyHash) {
                entry = virtualNode;
                break;
            }
        }

        if (entry == null && virtualNodeMapping.isEmpty()) {
            return null;
        }
        if (entry == null) {
            return virtualNodeMapping.get(virtualNodeMapping.keySet().iterator().next());
        }

        return entry.getValue();
    }

    public void addShard(String newShard, Set<Integer> nodeHashes) {
        for (final int nodeHash : nodeHashes) {
            virtualNodeMapping.put(nodeHash, newShard);
        }
    }

    private int hashKey(String key) {
        return hashService.digest(key.getBytes(StandardCharsets.UTF_8));
    }
}
