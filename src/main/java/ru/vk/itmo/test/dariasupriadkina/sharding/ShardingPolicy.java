package ru.vk.itmo.test.dariasupriadkina.sharding;

public interface ShardingPolicy {

    String getNodeById(String id);

}
