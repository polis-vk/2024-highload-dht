package ru.vk.itmo.test.pavelemelyanov;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> virtualNodeMapping = new TreeMap<>();
    private final HashService hashService = new HashService();
    private final int clusterSize;

    public ConsistentHashing(List<String> clusterUrls, int numbOfVirtualNodes) {
        clusterSize = clusterUrls.size();
        for (String clusterUrl : clusterUrls) {
            for (int i = 0; i < numbOfVirtualNodes; i++) {
                addNode(i, clusterUrl);
            }
        }
    }

    public void addNode(int numOfNode, String node) {
        int hash = getHash(node + numOfNode);
        virtualNodeMapping.put(hash, node);
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
        if (virtualNodeMapping.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> res = new ArrayList<>();

        if (key != null && from > 0) {
            if (from < clusterSize) {
                int slot = getHash(key);
                Iterator<String> it = new ClockwiseIterator(slot);
                while (it.hasNext() && res.size() < from) {
                    String part = it.next();
                    res.add(part);
                }
            } else {
                res.addAll(virtualNodeMapping.values());
            }
        }

        return res;
    }

    List<String> getNodes(String key, List<String> clusterUrls, int from) {
        Map<Integer, String> nodesHashes = new TreeMap<>();

        for (String nodeUrl : clusterUrls) {
            nodesHashes.put(getHash(nodeUrl + key), nodeUrl);
        }

        return nodesHashes.values().stream().limit(from)
                .collect(Collectors.toList());
    }

    private int getHash(String key) {
        return hashService.digest(key.getBytes(StandardCharsets.UTF_8));
    }

    private class ClockwiseIterator implements Iterator<String> {
        private final Iterator<String> head;
        private final Iterator<String> tail;

        public ClockwiseIterator(int slot) {
            this.head = virtualNodeMapping.headMap(slot)
                    .values()
                    .iterator();
            this.tail = virtualNodeMapping.tailMap(slot)
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
