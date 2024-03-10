package ru.vk.itmo.test.proninvalentin.sharding;

public interface ShardingAlgo {
    String getNodeByKey(String key);
}
