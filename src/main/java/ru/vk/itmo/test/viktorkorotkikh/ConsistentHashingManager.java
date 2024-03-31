package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.util.Hash;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashingManager {
    private final NavigableMap<Integer, String> hashRing;
    private final int clusterSize;

    public ConsistentHashingManager(int numberOfVNodes, List<String> clusterUrls) {
        hashRing = new TreeMap<>();
        clusterSize = clusterUrls.size();
        for (String clusterUrl : clusterUrls) {
            for (int j = 0; j < numberOfVNodes; j++) {
                final byte[] input = (clusterUrl + j).getBytes(StandardCharsets.UTF_8);
                hashRing.put(bytesToHash(input), clusterUrl);
            }
        }
    }

    public List<String> getReplicasList(final int from, final byte[] key) {
        if (from > clusterSize) {
            throw new IllegalArgumentException("Not enough servers in cluster");
        }

        final Set<String> replicas = new LinkedHashSet<>(from, 1.f);
        final int keyHash = bytesToHash(key);

        SortedMap<Integer, String> tailMap = hashRing.tailMap(keyHash);
        Iterator<Map.Entry<Integer, String>> ringIterator
                = (tailMap.isEmpty() ? hashRing : tailMap).entrySet().iterator();

        String targetServer = ringIterator.next().getValue();
        replicas.add(targetServer);
        int i = 1;

        while (i < from) {
            if (!ringIterator.hasNext()) { // end of iterator and we need to simulate real ring
                ringIterator = hashRing.entrySet().iterator();
            }

            String next = ringIterator.next().getValue();
            if (replicas.add(next)) {
                i++;
            }
        }
        return List.copyOf(replicas);
    }

    private static int bytesToHash(byte[] bytes) {
        return Hash.murmur3(bytes, 0, bytes.length);
    }
}
