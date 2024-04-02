package ru.vk.itmo.test.andreycheshev;

import one.nio.util.Hash;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

public class RendezvousDistributor {
    private final int nodeCount;
    private final int thisNodeNumber;

    public RendezvousDistributor(int nodeCount, int thisNodeNumber) {
        this.nodeCount = nodeCount;
        this.thisNodeNumber = thisNodeNumber;
    }

    private static int hashCode(int key) {
        int x = key;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x;
    }

    public ArrayList<Integer> getQuorumNodes(String stringKey, int quorumNumber) {
        PriorityQueue<HashPair> queue = new PriorityQueue<>(quorumNumber, Comparator.comparingInt(HashPair::getHash));
        int key = Hash.murmur3(stringKey);
        for (int i = 0; i < quorumNumber; i++) {
            queue.add(new HashPair(hashCode(key + i), i));
        }
        return queue.stream().map(HashPair::getIndex).collect(Collectors.toCollection(ArrayList::new));
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public int getQuorumNumber() {
        return nodeCount / 2 + 1;
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
