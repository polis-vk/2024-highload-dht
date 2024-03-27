package ru.vk.itmo.test.viktorkorotkikh;

import one.nio.util.Hash;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashingManager {
    private final NavigableMap<Integer, String> hashRing;

    public ConsistentHashingManager(int numberOfVNodes, List<String> clusterUrls) {
        hashRing = new TreeMap<>();
        for (String clusterUrl : clusterUrls) {
            for (int j = 0; j < numberOfVNodes; j++) {
                final byte[] input = (clusterUrl + j).getBytes(StandardCharsets.UTF_8);
                hashRing.put(bytesToHash(input), clusterUrl);
            }
        }
    }

    public String getServerByKey(final byte[] key) {
        final int keyHash = bytesToHash(key);
        SortedMap<Integer, String> tailMap = hashRing.tailMap(keyHash);
        return (tailMap.isEmpty() ? hashRing.firstEntry() : tailMap.firstEntry()).getValue();
    }

    private static int bytesToHash(byte[] bytes) {
        return Hash.murmur3(bytes, 0, bytes.length);
    }
}
