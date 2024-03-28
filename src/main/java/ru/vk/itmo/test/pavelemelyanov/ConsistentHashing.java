package ru.vk.itmo.test.pavelemelyanov;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class ConsistentHashing {
    private final NavigableMap<Integer, String> virtualNodeMapping = new TreeMap<>();
    private final HashService hashService = new HashService();

    public ConsistentHashing(List<String> clusterUrls, int numbOfVirtualNodes) {
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

        int hash = getHash(key);
        SortedMap<Integer, String> tailMap = virtualNodeMapping.tailMap(hash);
        var nodeEntry = tailMap.isEmpty() ? virtualNodeMapping.firstEntry() : tailMap.firstEntry();
        return nodeEntry.getValue();
    }

    public List<String> getNodes(String key, int from) {
        if (virtualNodeMapping.isEmpty()) {
            return new ArrayList<>();
        }

        int hash = getHash(key);
        SortedMap<Integer, String> tailMap = virtualNodeMapping.tailMap(hash);
        var nodesMap = tailMap.isEmpty() ? virtualNodeMapping : tailMap;
        return nodesMap.values()
                .stream()
                .limit(from)
                .collect(Collectors.toList());
    }

    public List<String> getNodes(String key, List<String> clusterUrls, int from) {
        Map<Integer, String> nodesHashes = new TreeMap<>();

        for (String nodeUrl : clusterUrls) {
            nodesHashes.put(getHash(nodeUrl + key), nodeUrl);
        }

        return nodesHashes.values()
                .stream()
                .limit(from)
                .collect(Collectors.toList());
    }

    private int getHash(String key) {
        return hashService.digest(key.getBytes(StandardCharsets.UTF_8));
    }
}
