package ru.vk.itmo.test.proninvalentin.sharding;

import java.util.List;

public record ShardingConfig(int virtualNodesNumber, List<String> clusterUrls) {
    public static ShardingConfig defaultConfig(List<String> clusterUrls) {
        return new ShardingConfig(50, clusterUrls);
    }
}
