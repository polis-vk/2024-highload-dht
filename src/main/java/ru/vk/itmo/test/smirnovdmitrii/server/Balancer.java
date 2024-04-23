package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.smirnovdmitrii.application.properties.DhtValue;

import java.util.ArrayList;
import java.util.Arrays;
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

    public String[] getNodeUrls(final String key, final int from) {
        if (from >= clusterSize()) {
            return clusterUrls.toArray(new String[0]);
        }
        final int[] set = new int[from];
        Arrays.fill(set, -1);
        final String[] result = new String[from];
        final int hash = Math.abs(Hash.murmur3(key));
        int cur = hash % partitionMapping.length;
        int size = 0;
        while (size < from) {
            if (add(set, partitionMapping[cur])) {
                result[size] = clusterUrls.get(partitionMapping[cur]);
                size++;
            }
            cur++;
        }
        return result;
    }

    private boolean add(final int[] set, final int element) {
        for (int i = 0; i < set.length; i++) {
            final int index = (element + i) % set.length;
            final int cur = set[index];
            if (cur == -1) {
                set[index] = element;
                return true;
            }
            if (element == cur) {
                return false;
            }
        }
        return false;
    }

    public int clusterSize() {
        return clusterUrls.size();
    }

    public int quorum(final int from) {
        return from / 2 + 1;
    }
}
