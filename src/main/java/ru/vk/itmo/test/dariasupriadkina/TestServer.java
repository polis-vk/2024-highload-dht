package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.dariasupriadkina.sharding.RendezvousHashing;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.CustomThreadConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ru.vk.itmo.test.dariasupriadkina.ServiceImlFactory.FLUSH_THRESHOLD_BYTES;

public final class TestServer {

    private static final String LOCALHOST_PREFIX = "http://localhost:";
    private static final int NODE_AMOUNT = 1;

    private TestServer() {
    }

    public static void main(String[] args) throws IOException, ExecutionException,
            InterruptedException, TimeoutException {
        Map<Integer, String> nodes = new HashMap<>();
        int nodePort = 8080;
        for (int i = 0; i < NODE_AMOUNT; i++) {
            nodes.put(nodePort, LOCALHOST_PREFIX + nodePort);
            nodePort += 10;
        }

        List<String> clusterUrls = new ArrayList<>(nodes.values());
        List<ServiceConfig> clusterConfs = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : nodes.entrySet()) {
            int port = entry.getKey();
            String url = entry.getValue();
            Path path = Paths.get("tmp/db/" + port);
            Files.createDirectories(path);
            ServiceConfig serviceConfig = new ServiceConfig(port,
                    url,
                    clusterUrls,
                    path);
            clusterConfs.add(serviceConfig);
        }
        ShardingPolicy shardingPolicy = new RendezvousHashing(
                clusterUrls
        );

        for (ServiceConfig serviceConfig : clusterConfs) {
            ServiceIml serviceIml = new ServiceIml(serviceConfig, new Config(serviceConfig.workingDir(),
                    FLUSH_THRESHOLD_BYTES),
                    CustomThreadConfig.baseConfig("worker-thread"), shardingPolicy,
                    CustomThreadConfig.baseConfig("node-thread"));
            serviceIml.start().get(2, TimeUnit.SECONDS);
        }
    }
}
