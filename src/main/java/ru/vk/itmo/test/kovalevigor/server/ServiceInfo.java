package ru.vk.itmo.test.kovalevigor.server;

import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;

import java.util.Collection;

public interface ServiceInfo {

    int getClusterSize();

    int getQuorum();

    ServerStrategy getPartitionStrategy(ServerStrategy caller, String key);

    Collection<ServerStrategy> getPartitionStrategy(ServerStrategy caller, String key, int count);

}
