package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.dariasupriadkina.sharding.ConsistentHashingShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerConfig;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ExecutionException;

public final class TestServer {

    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int QUEUE_SIZE = 1024;
    private static final int NUMBER_OF_REPLICAS = 100;

    private TestServer() {
    }

    public static void main(String[] args) throws IOException, ExecutionException, InterruptedException {
        String url = "http://localhost";
        ServiceConfig serviceConfig = new ServiceConfig(
                8080,
                url,
                List.of(url),
                Paths.get("./"));
        ShardingPolicy shardingPolicy = new ConsistentHashingShardingPolicy(
                serviceConfig.clusterUrls(), NUMBER_OF_REPLICAS
        );
      ServiceIml serviceIml = new ServiceIml(serviceConfig, new Config(serviceConfig.workingDir(),
              1024 * 1024), new WorkerConfig(THREADS, THREADS, QUEUE_SIZE, 30),
              shardingPolicy);
        serviceIml.start().get();
    }
}
