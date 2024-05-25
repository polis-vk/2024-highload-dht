package ru.vk.itmo.test.proninvalentin.sharding;

import one.nio.util.Hash;
import one.nio.util.Utf8;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConsistentHashing implements ShardingAlgorithm {
    private final int[] hashes;
    private final Map<Integer, String> hashToUrl = new HashMap<>();

    public ConsistentHashing(ShardingConfig config) {
        List<String> clusterUrls = config.clusterUrls();
        int virtualNodesNumber = config.virtualNodesNumber();
        int hashesNumber = clusterUrls.size() * virtualNodesNumber;
        this.hashes = new int[hashesNumber];
        initVirtualNodes(clusterUrls, virtualNodesNumber);
    }

    private void initVirtualNodes(List<String> clusterUrls, int virtualNodesNumber) {
        for (int i = 0; i < clusterUrls.size(); i++) {
            String nodeUrl = clusterUrls.get(i);
            for (int j = 0; j < virtualNodesNumber; j++) {
                int nodeNumber = virtualNodesNumber * i + j;
                String virtualNode = "[VN: " + nodeNumber + "]" + nodeUrl;
                int hash = hash(virtualNode);
                hashes[nodeNumber] = hash;
                hashToUrl.put(hash, nodeUrl);
            }
        }
        Arrays.sort(hashes);
    }

    @Override
    public List<String> getNodesByKey(String key, int necessaryNodeNumber) {
        int hash = hash(key);
        int nodeIndex = Arrays.binarySearch(hashes, hash);
        if (nodeIndex >= 0) {
            return getNecessaryNodes(nodeIndex, necessaryNodeNumber);
        }
        nodeIndex = -nodeIndex - 2;
        if (nodeIndex < 0) {
            return getNecessaryNodes(hashes.length - 1, necessaryNodeNumber);
        }
        return getNecessaryNodes(nodeIndex, necessaryNodeNumber);
    }

    private List<String> getNecessaryNodes(int firstNodeIndex, int necessaryNodeNumber) {
        Set<String> nodeUrls = new HashSet<>(necessaryNodeNumber);
        int virtualNodeNumber = hashes.length;
        int nodeIndex = firstNodeIndex;
        for (int i = 0; i < virtualNodeNumber && nodeUrls.size() < necessaryNodeNumber; i++, nodeIndex++) {
            String nodeUrl = hashToUrl.get(hashes[nodeIndex % virtualNodeNumber]);
            nodeUrls.add(nodeUrl);
        }
        return nodeUrls.stream().toList();
    }

    private int hash(String key) {
        byte[] keyBytes = Utf8.toBytes(key);
        return Hash.xxhash(keyBytes, 0, keyBytes.length);
    }
}

