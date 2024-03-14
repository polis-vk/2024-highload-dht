package ru.vk.itmo.test.alexeyshemetov.sharding;

public interface ShardingManager {
    String getClusterUrlByKey(String key);
}
