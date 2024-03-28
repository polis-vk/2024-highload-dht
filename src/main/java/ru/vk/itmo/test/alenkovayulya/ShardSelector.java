package ru.vk.itmo.test.alenkovayulya;

import one.nio.util.Hash;

import java.util.List;

public class ShardSelector {

    private final List<String> shardUrls;

    public ShardSelector(List<String> shardUrls) {
        this.shardUrls = shardUrls;
    }

    public int getOwnerShardIndex(String id) {
        int maxHash = Integer.MIN_VALUE;
        int maxHashShardIndex = -1;
        for (int i = 0; i < shardUrls.size(); i++) {
            int hash = Hash.murmur3(id + shardUrls.get(i));
            if (hash > maxHash) {
                maxHash = hash;
                maxHashShardIndex = i;
            }
        }
        return maxHashShardIndex;
    }

    public int getClusterSize() {
        return shardUrls.size();
    }

    public String getShardUrlByIndex(int index) {
        return shardUrls.get(index);
    }
}
