package ru.vk.itmo.test.osokindm;

import one.nio.util.Hash;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class RendezvousRouter {

    private final List<Node> nodes;

    public RendezvousRouter(List<String> nodeUrls) {
        nodes = new ArrayList<>();
        for (int i = 0; i < nodeUrls.size(); i++) {
            addNode(new Node(nodeUrls.get(i), i));
        }
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public Node getNode(String key) {
        if (key == null) {
            return null;
        }
        int max = Integer.MIN_VALUE;
        Node maxHashNode = null;
        for (Node node : nodes) {
            int hash = Hash.murmur3(node.name + key);
            if (hash > max) {
                max = hash;
                maxHashNode = node;
            }
        }
        return maxHashNode;
    }

    public List<Node> getNodes(String key, int nodeAmount) {
        if (key == null) {
            return null;
        }
        TreeMap<Integer, Node> sortedNodes = new TreeMap<>();
        for (Node node : nodes) {
            int hash = Hash.murmur3(node.name + key);
            sortedNodes.put(hash, node);
        }
        List<Node> selectedNodes = new ArrayList<>();
        int nodesNeeded = Math.min(nodeAmount, sortedNodes.size());
        for (Node node : sortedNodes.values()) {
            if (nodesNeeded == 0) break;
            selectedNodes.add(node);
            nodesNeeded--;
        }

        return selectedNodes;
    }

}
