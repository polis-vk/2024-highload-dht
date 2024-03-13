package ru.vk.itmo.test.volkovnikita;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;

public class DistributedHash<T> {

    private final NavigableMap<Integer, T> nodeRing = new TreeMap<>();
    private final List<T> clusterNodes = new ArrayList<>();
    private final Function<T, Integer> hasher;

    public DistributedHash(Function<T, Integer> hasher, List<T> clusterNodes) {
        this.hasher = hasher;
        clusterNodes.forEach(this::addClusterNode);
    }

    public void addClusterNode(T node) {
        int nodeHash = hasher.apply(node);
        nodeRing.put(nodeHash, node);
        clusterNodes.add(node);
    }

    public T getNode(Object key) {
        if (nodeRing.isEmpty()) {
            return null;
        }
        int keyHash = key.hashCode();
        Integer nearestKey = nodeRing.ceilingKey(keyHash);
        if (nearestKey == null) {
            nearestKey = nodeRing.firstKey();
        }
        return nodeRing.get(nearestKey);
    }

    public int getClusterNodeIndex(Object key) {
        T node = getNode(key);
        return clusterNodes.indexOf(node);
    }
}
