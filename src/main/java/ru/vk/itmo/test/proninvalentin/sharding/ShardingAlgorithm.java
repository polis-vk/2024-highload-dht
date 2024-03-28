package ru.vk.itmo.test.proninvalentin.sharding;

import java.util.List;

public interface ShardingAlgorithm {
    List<String> getNodesByKey(String key, int necessaryNodeNumber);
}
