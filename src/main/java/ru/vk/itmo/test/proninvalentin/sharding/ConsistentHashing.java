package ru.vk.itmo.test.proninvalentin.sharding;

import one.nio.util.Hash;
import one.nio.util.Utf8;

import java.util.*;

public class ConsistentHashing implements ShardingAlgorithm {
    private final int[] hashes;
    private final Map<Integer, String> vNodeMapping = new HashMap<>();

    public ConsistentHashing(ShardingConfig config) {
        List<String> nodesUrls = config.nodesUrls();
        int virtualNodesNumber = config.virtualNodesNumber();
        int hashesNumber = nodesUrls.size() * virtualNodesNumber;
        this.hashes = new int[hashesNumber];
        initVirtualNodes(nodesUrls, virtualNodesNumber);
    }

    private void initVirtualNodes(List<String> nodesUrls, int virtualNodesNumber) {
        for (int i = 0; i < nodesUrls.size(); i++) {
            String nodeUrl = nodesUrls.get(i);
            for (int j = 0; j < virtualNodesNumber; j++) {
                String virtualNode = "[VN: " + (virtualNodesNumber * i + i) + "]" + nodeUrl;
                int hash = hash(virtualNode);
                hashes[i * virtualNodesNumber + j] = hash;
                vNodeMapping.put(hash, nodeUrl);
            }
        }
        Arrays.sort(hashes);
    }

    @Override
    public String getNodeByKey(String key) {
        int hash = hash(key);
        int nodeIndex = Arrays.binarySearch(hashes, hash);
        if (nodeIndex >= 0)
        {
            return vNodeMapping.get(hashes[nodeIndex]);
        }
        nodeIndex = -nodeIndex - 2;
        if (nodeIndex < 0) {
            return vNodeMapping.get(hashes[hashes.length - 1]);
        }
        return vNodeMapping.get(hashes[nodeIndex]);
    }

    private int hash(String key) {
        byte[] keyBytes = Utf8.toBytes(key);
        return Hash.xxhash(keyBytes, 0, keyBytes.length);
    }
}

