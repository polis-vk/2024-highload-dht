package ru.vk.itmo.test.georgiidalbeev;

import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Function;

public class ConsistentHashing<T> {

    private final SortedMap<Integer, T> circle;
    private final Function<String, Integer> hashFunction;

    public ConsistentHashing(Function<String, Integer> hashFunction, List<T> nodes) {
        this.hashFunction = hashFunction;
        SortedMap<Integer, T> tempCircle = new TreeMap<>();
        for (T node : nodes) {
            int hash = hashFunction.apply((String) node);
            tempCircle.put(hash, node);
        }
        this.circle = Collections.unmodifiableSortedMap(tempCircle);
    }

    public T getNode(String key) {
        if (circle.isEmpty()) {
            return null;
        }
        int hash = hashFunction.apply(key);
        if (!circle.containsKey(hash)) {
            SortedMap<Integer, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }
}
