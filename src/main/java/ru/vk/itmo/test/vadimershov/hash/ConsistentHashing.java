package ru.vk.itmo.test.vadimershov.hash;

import one.nio.http.HttpClient;
import one.nio.net.ConnectionString;
import one.nio.util.Hash;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.vadimershov.dao.TimestampEntry;
import ru.vk.itmo.test.vadimershov.exceptions.FailedSharding;

import java.lang.foreign.MemorySegment;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

public class ConsistentHashing {

    private static final int VIRTUAL_NODE_COUNT = 128;

    private final SortedMap<Integer, VirtualNode> ring;

    private final VirtualNode localNode;

    public ConsistentHashing(
            String localUrl,
            List<String> clusterUrls,
            Dao<MemorySegment, TimestampEntry<MemorySegment>> localDao
    ) {
        this.ring = new TreeMap<>();
        this.localNode = new LocalNode(localUrl, localDao, -1);
        HashMap<String, HttpClient> httpClientMap = new HashMap<>();
        for (String currentUrl : clusterUrls) {
            if (Objects.equals(currentUrl, localUrl)) {
                for (int i = 0; i < VIRTUAL_NODE_COUNT; i++) {
                    VirtualNode virtualNode = new LocalNode(currentUrl, localDao, i);
                    ring.put(Hash.murmur3(virtualNode.key(currentUrl)), virtualNode);
                }
            } else {
                HttpClient currentHttpClient = httpClientMap.get(currentUrl);
                if (currentHttpClient == null) {
                    currentHttpClient = new HttpClient(new ConnectionString(currentUrl));
                    httpClientMap.put(currentUrl, currentHttpClient);
                }
                for (int i = 0; i < VIRTUAL_NODE_COUNT; i++) {
                    VirtualNode virtualNode = new RemoteNode(currentUrl, currentHttpClient, i);
                    ring.put(Hash.murmur3(virtualNode.key(currentUrl)), virtualNode);
                }
            }
        }
    }

    public VirtualNode getLocalNode() {
        return this.localNode;
    }

    public Collection<VirtualNode> findVNodes(String key, int count) {
        Integer hashKey = Hash.murmur3(key);
        HashMap<String, VirtualNode> selectedNodes = new HashMap<>();

        SortedMap<Integer, VirtualNode> sliceMap = ring.tailMap(hashKey);
        for (VirtualNode node : sliceMap.values()) {
            if (count == 0) {
                return selectedNodes.values();
            }

            if (selectedNodes.get(node.url()) != null) {
                continue;
            }
            selectedNodes.put(node.url(), node);
            count--;
        }

        sliceMap = ring.headMap(hashKey);
        for (VirtualNode node : sliceMap.values()) {
            if (count == 0) {
                return selectedNodes.values();
            }

            if (selectedNodes.get(node.url()) != null) {
                continue;
            }
            selectedNodes.put(node.url(), node);
            count--;
        }
        throw new FailedSharding();
    }

    public void close() {
        for (VirtualNode value : ring.values()) {
            value.close();
        }
    }
}
