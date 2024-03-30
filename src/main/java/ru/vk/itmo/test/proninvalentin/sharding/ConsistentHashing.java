package ru.vk.itmo.test.proninvalentin.sharding;

import one.nio.util.Hash;
import one.nio.util.Utf8;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ConsistentHashing implements ShardingAlgorithm {
    private final int[] hashes;
    private final Map<Integer, String> hashToNodeUrl = new HashMap<>();

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
                String virtualNode = "[VN: " + (virtualNodesNumber * i + j) + "]" + nodeUrl;
                int hash = generateUniqueHash(virtualNode);
                hashes[i * virtualNodesNumber + j] = hash;
                hashToNodeUrl.put(hash, nodeUrl);
            }
        }
        Arrays.sort(hashes);
    }

    private int generateUniqueHash(String virtualNode) {
        int hash = hash(virtualNode);
        for (int i = 0; hashToNodeUrl.containsKey(hash) && i != Integer.MAX_VALUE; i++) {
            hash = hash(virtualNode + i);
        }
        if (hashToNodeUrl.containsKey(hash)) {
            throw new IllegalArgumentException("Can't generate unique hash for virtual node: " + virtualNode);
        }
        return hash;
    }

    @Override
    public String getNodeByKey(String key) {
        int hash = hash(key);
        int nodeIndex = Arrays.binarySearch(hashes, hash);
        if (nodeIndex >= 0) {
            return hashToNodeUrl.get(hashes[nodeIndex]);
        }
        nodeIndex = -nodeIndex - 2;
        if (nodeIndex < 0) {
            return hashToNodeUrl.get(hashes[hashes.length - 1]);
        }
        return hashToNodeUrl.get(hashes[nodeIndex]);
    }

    private int hash(String key) {
        byte[] keyBytes = Utf8.toBytes(key);
        return Hash.xxhash(keyBytes, 0, keyBytes.length);
    }
}

