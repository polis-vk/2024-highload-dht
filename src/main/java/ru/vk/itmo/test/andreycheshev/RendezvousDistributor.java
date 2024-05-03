package ru.vk.itmo.test.andreycheshev;

import one.nio.util.Hash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

public class RendezvousDistributor {
    private final List<String> clusterUrls;
    private final int thisNodeNumber;
    private final int quorumNumber;
    private final ParametersTuple<Integer> defaultAckFrom;

    public RendezvousDistributor(List<String> clusterUrls, int thisNodeNumber) {
        this.clusterUrls = clusterUrls;
        this.thisNodeNumber = thisNodeNumber;
        this.quorumNumber = clusterUrls.size() / 2 + 1;
        this.defaultAckFrom = new ParametersTuple<>(quorumNumber, getNodeCount());
    }

    private static int hashCode(int key) {
        int x = key;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x;
    }

    public List<Integer> getNodesByKey(String stringKey, int number) {
        PriorityQueue<HashPair> queue = new PriorityQueue<>(
                number,
                Comparator.comparingInt(HashPair::getHash).reversed()
        );
        int key = Hash.murmur3(stringKey);
        for (int i = 0; i < number; i++) {
            queue.add(new HashPair(hashCode(key + i), i));
        }
        return queue.stream().map(HashPair::getIndex).collect(Collectors.toCollection(ArrayList::new));
    }

    public List<Integer> getQuorumNodesByKey(String stringKey) {
        return getNodesByKey(stringKey, quorumNumber);
    }

    public String getNodeUrlByIndex(int index) {
        return clusterUrls.get(index);
    }

    public int getNodeCount() {
        return clusterUrls.size();
    }

    public int getQuorumNumber() {
        return quorumNumber;
    }

    public ParametersTuple<Integer> getDefaultAckFrom() {
        return defaultAckFrom;
    }

    public boolean isOurNode(int node) {
        return node == thisNodeNumber;
    }

    private static class HashPair {
        int hash;
        int index;

        public HashPair(int hash, int index) {
            this.hash = hash;
            this.index = index;
        }

        public int getHash() {
            return hash;
        }

        public int getIndex() {
            return index;
        }
    }
}
