package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Random;

public class Balancer {
    private final List<String> clusterUrls;
    private final Logger logger = LoggerFactory.getLogger(Balancer.class);
    private int[] partitionMapping;
    @DhtValue("balancer.vnode.per.node")
    private static int vNodePerNode;
    @DhtValue("balancer.vnode.random.seed")
    private static int vNodeRandomSeed;

    public Balancer(List<String> clusterUrls) {
        this.clusterUrls = new ArrayList<>(clusterUrls);
        this.clusterUrls.sort(Comparator.naturalOrder());
        generatePartitionMap();
    }

    private void generatePartitionMap() {
        logger.info("starting creating key map for nodes.");
        partitionMapping = new int[vNodePerNode * clusterUrls.size()];
        final int clusterUrlsSize = clusterUrls.size();
        final Random random = new Random(vNodeRandomSeed);
        final int[] mappedCount = new int[clusterUrlsSize];
        int nodesDoneCount = 0;
        for (int i = 0; i < partitionMapping.length; i++) {
            int skip = random.nextInt(clusterUrlsSize - nodesDoneCount);
            int index = 0;
            while (skip != 0) {
                if (mappedCount[index] != vNodePerNode) {
                    index = (index + 1) % mappedCount.length;
                }
                skip--;
            }
            mappedCount[index]++;
            if (mappedCount[index] == vNodePerNode) {
                nodesDoneCount++;
            }
            partitionMapping[i] = index;
        }
        logger.info("creating key map for nodes is done.");
    }

    public String[] getNodeUrls() {
        return clusterUrls.toArray(new String[0]);
    }

    public String[] getNodeUrls(
            final String key,
            final int count
    ) {
        if (count >= clusterUrls.size()) {
            return getNodeUrls();
        }
        final String[] urls = new String[count];
        final int hash = Math.abs(Hash.murmur3(key));
        int vnodeIndex = hash % partitionMapping.length;
        int index = 0;
        while (index < urls.length) {
            final int cur = partitionMapping[vnodeIndex];
            final String curUrl = clusterUrls.get(cur);
            boolean isTaken = false;
            for (int i = 0; i < index; i++) {
                final String value = urls[i];
                if (Objects.equals(curUrl, value)) {
                    isTaken = true;
                    break;
                }
            }
            vnodeIndex = (vnodeIndex + 1) % partitionMapping.length;
            if (isTaken) {
                continue;
            }
            urls[index] = clusterUrls.get(cur);
            index++;
        }
        return urls;
    }

    public int clusterSize() {
        return clusterUrls.size();
    }

    public int quorum(final int from) {
        return from / 2 + 1;
    }
}
