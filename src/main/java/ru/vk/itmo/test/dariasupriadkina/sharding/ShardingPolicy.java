package ru.vk.itmo.test.dariasupriadkina.sharding;

import one.nio.util.Hash;

import java.util.List;

public abstract class ShardingPolicy {

    public abstract String getNodeById(String id);

    public abstract List<String> getNodesById(String id, int nodeCount);

    protected final int hash(String str) {
        return Hash.murmur3(str);
    }

}
