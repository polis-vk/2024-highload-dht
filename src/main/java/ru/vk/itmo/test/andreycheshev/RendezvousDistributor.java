package ru.vk.itmo.test.andreycheshev;

import one.nio.util.Hash;

public class RendezvousDistributor {
    private final int nodeCount;
    private final int thisNodeNumber;

    public RendezvousDistributor(int nodeCount, int thisNodeNumber) {
        this.nodeCount = nodeCount;
        this.thisNodeNumber = thisNodeNumber;
    }

    public int getNode(String stringKey) { // Rendezvous hashing algorithm.
        int key = Hash.murmur3(stringKey);
        int maxHash = Integer.MIN_VALUE;
        int node = 0;
        for (int i = 1; i <= nodeCount; i++) {
            int currHash = customHashCode(key + i);
            if (currHash > maxHash) {
                maxHash = currHash;
                node = thisNodeNumber == i ? -1 : i;
            }
        }
        return node; // Return -1 if this node was selected.
    }

    private static int customHashCode(int key) {
        int x = key;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x;
    }
}
