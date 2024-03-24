package ru.vk.itmo.test.proninvalentin.sharding;

import one.nio.util.Hash;
import one.nio.util.Utf8;
import ru.vk.itmo.test.proninvalentin.failure_limiter.FailureLimiter;

import java.util.*;

public class ConsistentHashing implements ShardingAlgorithm {
    private final int[] hashes;
    private final Map<Integer, String> virtualNodeMapping = new HashMap<>();
    private final FailureLimiter failureLimiter;
    private final List<String> clusterUrls;

    public ConsistentHashing(ShardingConfig config, FailureLimiter failureLimiter) {
        this.clusterUrls = config.clusterUrls();
        int virtualNodesNumber = config.virtualNodesNumber();
        int hashesNumber = clusterUrls.size() * virtualNodesNumber;
        this.hashes = new int[hashesNumber];
        initVirtualNodes(clusterUrls, virtualNodesNumber);
        this.failureLimiter = failureLimiter;
    }

    private void initVirtualNodes(List<String> clusterUrls, int virtualNodesNumber) {
        for (int i = 0; i < clusterUrls.size(); i++) {
            String nodeUrl = clusterUrls.get(i);
            for (int j = 0; j < virtualNodesNumber; j++) {
                int nodeNumber = virtualNodesNumber * i + j;
                String virtualNode = "[VN: " + nodeNumber + "]" + nodeUrl;
                int hash = hash(virtualNode);
                hashes[nodeNumber] = hash;
                virtualNodeMapping.put(hash, nodeUrl);
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

    private List<String> getNecessaryNodes(int nodeIndex, int necessaryNodeNumber) {
        List<String> nodeUrls = new ArrayList<>(necessaryNodeNumber);
        int clusterSize = clusterUrls.size();
        for (int i = 0; i < clusterSize && nodeUrls.size() < necessaryNodeNumber; i++, nodeIndex++) {
            String nodeUrl = clusterUrls.get(nodeIndex % clusterSize);
            if (failureLimiter.readyForRequests(nodeUrl)) {
                nodeUrls.add(nodeUrl);
            }
        }
        return nodeUrls;
    }

    private int hash(String key) {
        byte[] keyBytes = Utf8.toBytes(key);
        return Hash.xxhash(keyBytes, 0, keyBytes.length);
    }
}

