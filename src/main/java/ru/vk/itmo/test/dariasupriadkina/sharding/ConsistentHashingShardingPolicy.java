package ru.vk.itmo.test.dariasupriadkina.sharding;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashingShardingPolicy extends ShardingPolicy {

    private final SortedMap<Integer, String> circle = new TreeMap<>();
    private final int numberOfReplicas;

    public ConsistentHashingShardingPolicy(List<String> nodes, int numberOfReplicas) {
        this.numberOfReplicas = numberOfReplicas;
        for (String node : nodes) {
            add(node);
        }
    }

    public final void add(String node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            circle.put(hash(node + i) * 11, node);
        }
    }

    public final void remove(String node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            circle.remove(hash(node + i) * 11);
        }
    }

    @Override
    public String getNodeById(String key) {
        if (circle.isEmpty()) {
            return null;
        }

        int hash = hash(key) * 11;
        if (!circle.containsKey(hash)) {
            SortedMap<Integer, String> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }

}

