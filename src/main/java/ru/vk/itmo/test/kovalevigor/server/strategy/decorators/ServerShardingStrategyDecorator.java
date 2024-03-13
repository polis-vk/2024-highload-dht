package ru.vk.itmo.test.kovalevigor.server.strategy.decorators;

import one.nio.http.HttpSession;
import one.nio.http.Request;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerRemoteStrategy;
import ru.vk.itmo.test.kovalevigor.server.strategy.ServerStrategy;
import ru.vk.itmo.test.kovalevigor.server.util.Parameters;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ServerShardingStrategyDecorator extends ServerStrategyDecorator {

    public static final int PARTITIONS = 10000;
    private final List<Partition> clusterPartitions;

    private static final class Partition {
        public final ServerStrategy strategy;
        public final int start;
        public final int end;

        private Partition(ServerStrategy strategy, int start, int end) {
            this.strategy = strategy;
            this.start = start;
            this.end = end;
        }
    }

    public ServerShardingStrategyDecorator(
            ServerStrategy httpServer,
            List<String> clusterUrls,
            String clusterUrl
    ) {
        super(httpServer);
        int clusterSize = clusterUrls.size();
        this.clusterPartitions = new ArrayList<>(clusterSize);

        int step = PARTITIONS / clusterUrls.size();
        int offset = 0;
        for (int i = 0; i < clusterSize; i++) {
            int nextOffset = i + 1 == clusterSize ? PARTITIONS : offset + step;
            String url = clusterUrls.get(i);
            clusterPartitions.add(
                    new Partition(
                            url.equals(clusterUrl) ? this : new ServerRemoteStrategy(url),
                            offset,
                            nextOffset
                    )
            );
            offset = nextOffset;
        }
    }

    @Override
    public void handleRequest(Request request, HttpSession session) throws IOException {
        ServerStrategy strategy = getPartitionStrategy(Parameters.getParameter(request, Parameters.ID));
        if (strategy == this) {
            super.handleRequest(request, session);
        } else {
            strategy.handleRequest(request, session);
        }
    }

    private ServerStrategy getPartitionStrategy(String key) {
        int hashcode = Math.abs(key.hashCode() % PARTITIONS);
        int l = 0;
        int r = clusterPartitions.size();
        while (true) {
            int m = (r + l) / 2;
            Partition partition = clusterPartitions.get(m);
            if (partition.start <= hashcode && hashcode < partition.end) {
                return partition.strategy;
            } else if (hashcode >= partition.end) {
                l = m;
            } else {
                r = m;
            }
        }
    }

    @Override
    public void close() throws IOException {
        for (Partition clusterClient : clusterPartitions) {
            ServerStrategy strategy = clusterClient.strategy;
            if (strategy == this) {
                super.close();
            } else {
                strategy.close();
            }
        }
    }
}
