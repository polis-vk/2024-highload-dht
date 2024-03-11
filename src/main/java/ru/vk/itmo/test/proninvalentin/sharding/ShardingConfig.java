package ru.vk.itmo.test.proninvalentin.sharding;

import java.util.List;

public record ShardingConfig(int virtualNodesNumber, List<String> nodesUrls) {
    public static ShardingConfig defaultConfig(List<String> nodesUrls) {
        return new ShardingConfig(5, nodesUrls);
    }
}
