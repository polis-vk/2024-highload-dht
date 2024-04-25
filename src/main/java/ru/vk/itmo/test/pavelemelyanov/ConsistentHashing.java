package ru.vk.itmo.test.pavelemelyanov;

import one.nio.util.Hash;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> virtualNodeMapping = new TreeMap<>();
    private final List<String> clusterUrls;

    public ConsistentHashing(List<String> clusterUrls, int numbOfVirtualNodes) {
        this.clusterUrls = clusterUrls;
        for (String clusterUrl : clusterUrls) {
            for (int i = 0; i < numbOfVirtualNodes; i++) {
                addNode(i, clusterUrl);
            }
        }
    }

    public String getNode(String key) {
        if (virtualNodeMapping.isEmpty()) {
            return null;
        }

        final int hash = getHash(key);
        SortedMap<Integer, String> tailMap = virtualNodeMapping.tailMap(hash);
        return (tailMap.isEmpty() ? virtualNodeMapping.firstEntry() : tailMap.firstEntry()).getValue();
    }

    public List<String> getNodes(String key, int from) {
        List<String> res = new ArrayList<>();

        if (key != null && from > 0) {
            if (from < clusterUrls.size()) {
                int slot = getHash(key);
                Iterator<String> it = new ClockwiseIterator(slot);
                while (it.hasNext() && res.size() < from) {
                    String part = it.next();
                    res.add(part);
                }
            } else {
                res.addAll(clusterUrls);
            }
        }

        return res;
    }

    private int getHash(String key) {
        return Hash.murmur3(key);
    }

    private void addNode(int numOfNode, String node) {
        int hash = getHash(node + numOfNode);
        virtualNodeMapping.put(hash, node);
    }

    private class ClockwiseIterator implements Iterator<String> {
        private final Iterator<String> head;
        private final Iterator<String> tail;

        public ClockwiseIterator(int slot) {
            head = virtualNodeMapping.headMap(slot)
                    .values()
                    .iterator();
            tail = virtualNodeMapping.tailMap(slot)
                    .values()
                    .iterator();
        }

        @Override
        public boolean hasNext() {
            return head.hasNext() || tail.hasNext();
        }

        @Override
        public String next() {
            return tail.hasNext() ? tail.next() : head.next();
        }
    }
}
