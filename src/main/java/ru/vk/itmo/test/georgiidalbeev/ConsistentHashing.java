package ru.vk.itmo.test.georgiidalbeev;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

public class ConsistentHashing<T> {

    private final SortedMap<Integer, T> circle = new TreeMap<>();
    private final List<T> nodes = new ArrayList<>();
    private final Function<T, Integer> hashFunction;

    public ConsistentHashing(Function<T, Integer> hashFunction, List<T> nodes) {
        this.hashFunction = hashFunction;
        nodes.forEach(this::addNode);
    }

    public void addNode(T node) {
        int hash = hashFunction.apply(node);
        circle.put(hash, node);
        nodes.add(node);
    }

    public T getNode(String key) {
        if (circle.isEmpty()) {
            return null;
        }
        int hash = hashFunction.apply((T) key);
        if (!circle.containsKey(hash)) {
            SortedMap<Integer, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }

    public int getNodeIndex(String key) {
        if (circle.isEmpty()) {
            return -1;
        }
        int hash = hashFunction.apply((T) key);
        if (!circle.containsKey(hash)) {
            SortedMap<Integer, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        T node = circle.get(hash);
        return nodes.indexOf(node);
    }
}