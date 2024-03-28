package ru.vk.itmo.test.tuzikovalexandr;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {
    private final SortedMap<Integer, String> circle;

    public ConsistentHashing(List<String> clusterUrls, int numbOfVirtualNodes) {
        circle = new TreeMap<>();

        for (String clusterUrl : clusterUrls) {
            for (int i = 0; i < numbOfVirtualNodes; i++) {
                addNode(i, clusterUrl);
            }
        }
    }

    public void addNode(int numOfNode, String node) {
        int hash = getHash(numOfNode + node);
        circle.put(hash, node);
    }

    public String getNode(String key) {
        if (circle.isEmpty()) {
            return null;
        }

        int hash = getHash(key);
        if (!circle.containsKey(hash)) {
            SortedMap<Integer, String> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }

        return circle.get(hash);
    }

    private int getHash(String key) {
        return key.hashCode();
    }
}
