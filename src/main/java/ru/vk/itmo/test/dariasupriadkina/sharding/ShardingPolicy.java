package ru.vk.itmo.test.dariasupriadkina.sharding;

import one.nio.util.Hash;

public abstract class ShardingPolicy {

    public abstract String getNodeById(String id);

    protected final int hash(String str) {
        return Hash.murmur3(str);
    }

}
