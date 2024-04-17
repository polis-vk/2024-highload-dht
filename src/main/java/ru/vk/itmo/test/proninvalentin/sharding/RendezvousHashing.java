package ru.vk.itmo.test.proninvalentin.sharding;

import one.nio.util.Hash;

import java.util.List;

public class RendezvousHashing implements ShardingAlgorithm {

    private final List<String> clusterUrls;
    private final int[] urlHashes;

    public RendezvousHashing(List<String> clusterUrls) {
        this.clusterUrls = clusterUrls;
        this.urlHashes = new int[clusterUrls.size()];

        for (int i = 0; i < clusterUrls.size(); i++) {
            String clusterUrl = clusterUrls.get(i);
            urlHashes[i] = Hash.murmur3(clusterUrl);
        }
    }

    @Override
    public String getNodeByKey(final String entityId) {
        int maxHash = Integer.MIN_VALUE;
        int nodeId = 0;
        for (int i = 0; i < clusterUrls.size(); i++) {
            int urlPart = urlHashes[i];
            int pairHash = Hash.murmur3(urlPart + entityId);
            if (maxHash < pairHash) {
                maxHash = pairHash;
                nodeId = i;
            }
        }
        return clusterUrls.get(nodeId);
    }
}

