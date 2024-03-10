package ru.vk.itmo.test.proninvalentin.sharding;

import java.util.List;

public record ShardingConfig(int virtualNodesNumber, List<String> nodesUrls) {
}
