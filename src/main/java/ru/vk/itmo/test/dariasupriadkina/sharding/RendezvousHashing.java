package ru.vk.itmo.test.dariasupriadkina.sharding;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class RendezvousHashing extends ShardingPolicy {

    private final List<String> nodes;

    public RendezvousHashing(List<String> nodes) {
        this.nodes = nodes;
    }

    @Override
    public String getNodeById(String id) {
        int nodeId = 0;
        int maxHash = hash(nodes.getFirst() + id);
        for (int i = 1; i < nodes.size(); i++) {
            String url = nodes.get(i);
            int result = hash(url + id);
            if (maxHash < result) {
                maxHash = result;
                nodeId = i;
            }
        }
        return nodes.get(nodeId);
    }

    @Override
    public List<String> getNodesById(String id, int nodeAmount) {
        LinkedHashMap<String, Integer> sortedNodes = new LinkedHashMap<>();
        for (String node : nodes) {
            sortedNodes.put(node, hash(node + id));
        }
        return sortedNodes.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList())
                .subList(0, nodeAmount);
    }
}
