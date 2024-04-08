package ru.vk.itmo.test.trofimovmaxim;

import one.nio.util.Hash;

import java.util.List;

public class RendezvousBalancer {
    private final List<String> clusters;

    public RendezvousBalancer(List<String> clusterUrls) {
        this.clusters = clusterUrls;
    }

    public String getCluster(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        long maxHash = Long.MIN_VALUE;
        int maxIndex = -1;

        for (int i = 0; i < clusters.size(); ++i) {
            int hash = Hash.murmur3(clusters.get(i) + key);
            if (hash > maxHash) {
                maxHash = hash;
                maxIndex = i;
            }
        }

        if (maxIndex == -1) {
            return null;
        }
        return clusters.get(maxIndex);
    }
}
