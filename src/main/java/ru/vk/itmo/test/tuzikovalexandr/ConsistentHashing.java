package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.util.Hash;

import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> circle;

    public ConsistentHashing(List<String> clusterUrls, int numbOfVirtualNodes) {
        circle = new TreeMap<>();

        for (String clusterUrl : clusterUrls) {
            for (int i = 0; i < numbOfVirtualNodes; i++) {
                addNode(i, clusterUrl);
            }
        }
    }

    public void addNode(int numOfNode, String node) {
        final int hash = getHash(node + numOfNode);
        circle.put(hash, node);
    }

    public String getNode(String key) {
        if (circle.isEmpty()) {
            return null;
        }

        final int hash = getHash(key);
        SortedMap<Integer, String> tailMap = circle.tailMap(hash);
        return (tailMap.isEmpty() ? circle.firstEntry() : tailMap.firstEntry()).getValue();

//        if (!circle.containsKey(hash)) {
//            SortedMap<Integer, String> tailMap = circle.tailMap(hash);
//            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
//        }
//
//        return circle.get(hash);
    }

    private int getHash(String key) {
        return Hash.murmur3(key);
    }
}
