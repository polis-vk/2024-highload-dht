package ru.vk.itmo.test.vadimershov.hash;

import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;

import java.util.HashMap;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {

    private static final int VIRTUAL_NODE_COUNT = 128;

    private final SortedMap<Integer, VirtualNode> ring;

    public ConsistentHashing(List<String> clusterUrls) {
        this.ring = new TreeMap<>();
        HashMap<String, HttpClient> httpClientMap = new HashMap<>();
        for (String currentUrl : clusterUrls) {
            HttpClient currentHttpClient = httpClientMap.get(currentUrl);
            if (currentHttpClient == null) {
                currentHttpClient = new HttpClient(new ConnectionString(currentUrl));
                httpClientMap.put(currentUrl, currentHttpClient);
            }
            for (int j = 0; j < VIRTUAL_NODE_COUNT; j++) {
                VirtualNode virtualNode = new VirtualNode(currentUrl, currentHttpClient, j);
                ring.put(Hash.murmur3(virtualNode.key()), virtualNode);
            }
        }
    }

    public VirtualNode findVNode(String key) {
        Integer hashKey = Hash.murmur3(key);
        SortedMap<Integer, VirtualNode> tailMap = ring.tailMap(hashKey);
        Integer nodeHashVal;
        if (tailMap.isEmpty()) {
            nodeHashVal = ring.firstKey();
        } else {
            nodeHashVal = tailMap.firstKey();
        }
        return ring.get(nodeHashVal);
    }

    public void close() {
        for (VirtualNode value : ring.values()) {
            value.close();
        }
    }
}
