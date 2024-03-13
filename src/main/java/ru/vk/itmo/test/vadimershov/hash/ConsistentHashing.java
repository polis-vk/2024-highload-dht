package ru.vk.itmo.test.vadimershov.hash;

import one.nio.util.Hash;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {

    private static final int VIRTUAL_NODE_COUNT = 5;

    private final SortedMap<Integer, VNode> ring;

    public ConsistentHashing(List<String> clusterUrls) {
        this.ring = new TreeMap<>();
        for (String currentUrl : clusterUrls) {
            int existingReplicas = countReplicas(currentUrl);
            for (int j = 0; j < VIRTUAL_NODE_COUNT; j++) {
                VNode vNode = new VNode(currentUrl, j + existingReplicas);
                ring.put(Hash.murmur3(vNode.key()), vNode);
            }
        }
    }

    public VNode findVNode(String key) {
        Integer hashKey = Hash.murmur3(key);
        SortedMap<Integer, VNode> tailMap = ring.tailMap(hashKey);
        Integer nodeHashVal = !tailMap.isEmpty() ? tailMap.firstKey() : ring.firstKey();
        return ring.get(nodeHashVal);
    }

    private int countReplicas(String url) {
        int replicas = 0;
        for (VNode vNode : ring.values()) {
            if (vNode.url().equals(url)) {
                replicas++;
            }
        }
        return replicas;
    }

}
