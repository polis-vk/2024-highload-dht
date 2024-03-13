package ru.vk.itmo.test.tuzikovalexandr;

import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {
    private final SortedMap<Integer, String> circle = new TreeMap<>();

    public ConsistentHashing() {

    }

    public void addNode(String node) {
        int hash = getHash(node);
        circle.put(hash, node);
    }

    public void removeNode(String node) {
        int hash = getHash(node);
        circle.remove(hash);
    }

    public String getNode(Object key) {
        if (circle.isEmpty()) {
            return null;
        }

        int hash = getHash(key.toString());
        if (!circle.containsKey(hash)) {
            hash = circle.firstKey();
        }

        return circle.get(hash);
    }

    private int getHash(String key) {
        return key.hashCode();
    }
}
