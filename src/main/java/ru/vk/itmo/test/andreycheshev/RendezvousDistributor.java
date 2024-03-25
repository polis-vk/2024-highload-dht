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
        for (int i = 0; i < nodeCount; i++) {
            int currHash = hashCode(key + i);
            if (currHash > maxHash) {
                maxHash = currHash;
                node = i;
            }
        }
        return node;
    }

    public int[] getQuorumNodes(String key, int quorumNumber) {
        int currNode = getNode(key); // Get start node.
        int[] quorumNodes = new int[quorumNumber];
        for (int i = 0; i < quorumNumber; i++) {
            quorumNodes[i] = currNode;
            if (++currNode >= nodeCount) {
                currNode = 0;
            }
        }
        return quorumNodes;
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

    private static int hashCode(int key) {
        int x = key;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x;
    }
}
