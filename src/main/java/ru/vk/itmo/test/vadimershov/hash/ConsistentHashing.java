package ru.vk.itmo.test.vadimershov.hash;

import one.nio.util.Hash;

import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {

    private static final int VIRTUAL_NODE_COUNT = 5;

    private final SortedMap<Integer, VirtualNode> ring;

    public ConsistentHashing(List<String> clusterUrls) {
        this.ring = new TreeMap<>();
        for (String currentUrl : clusterUrls) {
            int existingReplicas = countReplicas(currentUrl);
            for (int j = 0; j < VIRTUAL_NODE_COUNT; j++) {
                VirtualNode virtualNode = new VirtualNode(currentUrl, j + existingReplicas);
                ring.put(Hash.murmur3(virtualNode.key()), virtualNode);
            }
        }
    }

    public VirtualNode findVNode(String key) {
        Integer hashKey = Hash.murmur3(key);
        SortedMap<Integer, VirtualNode> tailMap = ring.tailMap(hashKey);
        Integer nodeHashVal = (!tailMap.isEmpty()) ? tailMap.firstKey() : ring.firstKey();
        return ring.get(nodeHashVal);
    }

    private int countReplicas(String url) {
        int replicas = 0;
        for (VirtualNode virtualNode : ring.values()) {
            if (virtualNode.url().equals(url)) {
                replicas++;
            }
        }
        return replicas;
    }

}
