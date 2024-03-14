package ru.vk.itmo.test.smirnovandrew;

import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;

import java.util.List;
import java.util.Objects;

public class RendezvousClusterManager {

    private final List<String> availableClusters;

    public RendezvousClusterManager(ServiceConfig config) {
        this.availableClusters = config.clusterUrls();
    }

    public String getCluster(String key) {
        if (Objects.isNull(key) || key.isEmpty()) {
            return null;
        }

        int resIdx = -1;
        int maxHash = Integer.MIN_VALUE;
        for (int i = 0; i < availableClusters.size(); ++i) {
            var hash = Hash.murmur3(String.join("", availableClusters.get(i), key));
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
}
