package ru.vk.itmo.test.proninvalentin;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.proninvalentin.sharding.ConsistentHashing;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingConfig;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPoolConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class StartServer {
    private StartServer() {
    }

    public static void main(String[] args) throws IOException {
        String url = "http://localhost";
        int port = 8080;
        int flushThresholdBytes = 1 << 20; // 1 MB
        Path profilingDataPath = Path.of(
                "/Users/valentinpronin/IdeaProjects/2024-highload-dht/"
                        + "src/main/java/ru/vk/itmo/test/"
                        + "proninvalentin/server_profiling_data");
        Files.createDirectories(profilingDataPath);

        Config daoConfig = new Config(profilingDataPath, flushThresholdBytes);
        ReferenceDao referenceDao = new ReferenceDao(daoConfig);

        WorkerPoolConfig workerPoolConfig = WorkerPoolConfig.defaultConfig();
        WorkerPool workerPool = new WorkerPool(workerPoolConfig);

        List<String> nodesUrls = List.of(
                url + ":" + port,
                url + ":" + "44444",
                url + ":" + "55555"
        );
        ShardingConfig shardingConfig = ShardingConfig.defaultConfig(nodesUrls);
        ShardingAlgorithm shardingAlgorithm = new ConsistentHashing(shardingConfig);

        ServiceConfig serviceConfig = new ServiceConfig(port, url, nodesUrls, profilingDataPath);
        Server server = new Server(serviceConfig, referenceDao, workerPool, shardingAlgorithm,
                ServerConfig.defaultConfig());
        server.start();
    }
}
