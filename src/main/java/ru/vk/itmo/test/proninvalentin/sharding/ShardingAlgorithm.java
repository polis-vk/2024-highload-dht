package ru.vk.itmo.test.proninvalentin.sharding;

public interface ShardingAlgorithm {
    String getNodeByKey(String key);
}
