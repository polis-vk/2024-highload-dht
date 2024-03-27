package ru.vk.itmo.test.alenkovayulya;

import one.nio.util.Hash;

import java.util.List;

public class ShardSelector {

    private final List<String> shardUrls;

    public ShardSelector(List<String> shardUrls) {
        this.shardUrls = shardUrls;
    }

    public String getOwnerShardUrl(String id) {
        int maxHash = Integer.MIN_VALUE;
        String maxHashShardUrl = null;
        for (String shard : shardUrls) {
            int hash = Hash.murmur3(id + shard);
            if (hash > maxHash) {
                maxHash = hash;
                maxHashShardUrl = shard;
            }
        }
        return maxHashShardUrl;
    }
}
