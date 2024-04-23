package ru.vk.itmo.test.smirnovandrew;

import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class RendezvousClusterManager {

    private final List<String> availableClusters;

    public RendezvousClusterManager(ServiceConfig config) {
        this.availableClusters = config.clusterUrls();
    }

    public String getCluster(String key) {
        int resIdx = -1;
        int maxHash = Integer.MIN_VALUE;
        for (int i = 0; i < availableClusters.size(); ++i) {
            var hash = Hash.murmur3(key + availableClusters.get(i));
            if (hash > maxHash) {
                resIdx = i;
                maxHash = hash;
            }
        }

        if (resIdx == -1) {
            return null;
        }

        return availableClusters.get(resIdx);
    }

    public static List<Integer> getSortedNodes(String key, int amount, ServiceConfig config) {
        var result = new ArrayList<Integer>();
        for (int i = 0; i < config.clusterUrls().size() && result.size() < amount; i++) {
            result.add(i);
        }
        return result;
    }
}
