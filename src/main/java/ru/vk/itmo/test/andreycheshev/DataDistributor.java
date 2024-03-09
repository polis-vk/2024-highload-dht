package ru.vk.itmo.test.andreycheshev;

public class DataDistributor {
    private final int nodeCount;
    private final int thisNodeNumber;

    public DataDistributor(int nodeCount, int thisNodeNumber) {
        this.nodeCount = nodeCount;
        this.thisNodeNumber = thisNodeNumber;
    }

    public int getNode(String stringKey) { // Rendezvous hashing algorithm.
        int key = stringToIntKey(stringKey);
        int maxHash = Integer.MIN_VALUE;
        int node = 0;
        for (int i = 1; i <= nodeCount; i++) {
            int currHash = hashCode(key + i);
            if (currHash > maxHash) {
                maxHash = currHash;
                node = thisNodeNumber == i ? -1 : i;
            }
        }
        return node; // Return -1 if this node was selected.
    }

    private int stringToIntKey(String stringKey) {
        int intKey = 0;
        for (int i = 0; i < stringKey.length(); i++) {
            intKey += stringKey.charAt(i);
        }
        return intKey;
    }

    public static int hashCode(int key) {
        int x = key;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = ((x >> 16) ^ x) * 0x45d9f3b;
        x = (x >> 16) ^ x;
        return x;
    }
}
