package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
            while (true) {
                while (mappedCount[index] == vNodePerNode) {
                    index = (index + 1) % mappedCount.length;
                }
                if (skip == 0) {
                    break;
                }
                index = (index + 1) % mappedCount.length;
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

    public String getNodeUrl(final String key) {
        final int hash = Math.abs(Hash.murmur3(key));
        return clusterUrls.get(partitionMapping[hash % partitionMapping.length]);
    }
}
