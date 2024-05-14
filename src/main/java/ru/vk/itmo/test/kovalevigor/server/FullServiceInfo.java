package ru.vk.itmo.test.kovalevigor.server;

import ru.vk.itmo.test.kovalevigor.server.strategy.ServerRemoteStrategy;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Partition;

import java.io.IOException;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class FullServiceInfo implements ServiceInfo, AutoCloseable {

    public static final int PARTITIONS = 10000;
    private final List<Partition> clusterPartitions;

    public FullServiceInfo(
            HttpClient httpClient,
            List<String> clusterUrls,
            String clusterUrl
    ) {
        int clusterSize = clusterUrls.size();
        this.clusterPartitions = new ArrayList<>(clusterSize);

        int step = PARTITIONS / clusterUrls.size();
        int offset = 0;
        for (int i = 0; i < clusterSize; i++) {
            int nextOffset = i + 1 == clusterSize ? PARTITIONS : offset + step;
            String url = clusterUrls.get(i);
            clusterPartitions.add(
                    new Partition(
                            url.equals(clusterUrl) ? null : new ServerRemoteStrategy(httpClient, url),
                            offset,
                            nextOffset
                    )
            );
            offset = nextOffset;
        }
    }

    @Override
    public int getClusterSize() {
        return clusterPartitions.size();
    }

    @Override
    public int getQuorum() {
        return Math.min(getClusterSize() / 2 + 1, getClusterSize());
    }

    @Override
    public ServerStrategy getPartitionStrategy(ServerStrategy caller, String key) {
        return getStrategy(caller, getPartitionStrategyIndex(key));
    }

    @Override
    public Collection<ServerStrategy> getPartitionStrategy(ServerStrategy caller, String key, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count should be positive");
        }
        int clusterSize = getClusterSize();
        int firstIndex = getPartitionStrategyIndex(key);
        List<ServerStrategy> strategies = new ArrayList<>(Math.min(count, clusterSize));
        clusterPartitions.subList(firstIndex, Math.min(firstIndex + count, clusterSize))
                .forEach(p -> strategies.add(getStrategy(caller, p)));
        if (strategies.size() < count) {
            clusterPartitions.subList(0, clusterSize - strategies.size())
                    .forEach(p -> strategies.add(getStrategy(caller, p)));
        }
        return strategies;
    }

    @Override
    public void close() throws IOException {
        for (Partition clusterClient : clusterPartitions) {
            ServerStrategy strategy = clusterClient.strategy;
            if (strategy != null) {
                strategy.close();
            }
        }
    }

    private int getPartitionStrategyIndex(String key) {
        int hashcode = Math.abs(key.hashCode() % PARTITIONS);
        int l = 0;
        int r = clusterPartitions.size();
        while (true) {
            int m = (r + l) / 2;
            Partition partition = clusterPartitions.get(m);
            if (partition.start <= hashcode && hashcode < partition.end) {
                return m;
            } else if (hashcode >= partition.end) {
                l = m;
            } else {
                r = m;
            }
        }
    }

    private static ServerStrategy getStrategy(ServerStrategy caller, Partition partition) {
        return partition.strategy == null ? caller : partition.strategy;
    }

    private ServerStrategy getStrategy(ServerStrategy caller, int index) {
        return getStrategy(caller, clusterPartitions.get(index));
    }
}
