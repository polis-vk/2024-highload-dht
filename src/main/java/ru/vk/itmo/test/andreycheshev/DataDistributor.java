package ru.vk.itmo.test.andreycheshev;

public class DataDistributor {
    private final int nodeCount;
    private final String selfUrl;

    public DataDistributor(int nodeCount, String selfUrl) {
        this.nodeCount = nodeCount;
        this.selfUrl = selfUrl;
    }

    public int getNode(String stringKey) { // Rendezvous hashing algorithm.
        int key = stringToIntKey(stringKey);
        int maxHash = Integer.MIN_VALUE;
        int node = 0;
        for (int i = 0; i < nodeCount; i++) {
            int currHash = hashCode(i + key);
            if (currHash > maxHash) {
                node = selfUrl.equals(stringKey) ? -1 : i;
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

    private static int hashCode(int key) { // Multiplicative method.
        double f = key * Math.random();
        f = f - (int) f;
        return (int) (f * 1024);
    }
}
