package ru.vk.itmo.test.proninvalentin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.proninvalentin.dao.ReferenceDao;
import ru.vk.itmo.test.proninvalentin.failure_limiter.FailureLimiter;
import ru.vk.itmo.test.proninvalentin.failure_limiter.FailureLimiterConfig;
import ru.vk.itmo.test.proninvalentin.sharding.ConsistentHashing;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingConfig;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPoolConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class StartServer {
    private static final Logger logger = LoggerFactory.getLogger(StartServer.class);

    private StartServer() {
    }

    public static void main(String[] args) throws IOException {
        WorkerPoolConfig workerPoolConfig = WorkerPoolConfig.defaultConfig();
        WorkerPool workerPool = new WorkerPool(workerPoolConfig);

        int initialPort = 8080;
        int clusterSize = 3;
        int[] ports = new int[clusterSize];
        for (int i = 0; i < clusterSize; i++) {
            ports[i] = initialPort;
            initialPort += 1000;
        }

        List<String> clusterUrls = new ArrayList<>();
        String url = "http://localhost:";
        for (int i = 0; i < clusterSize; i++) {
            clusterUrls.add(url + ports[i]);
        }


        FailureLimiterConfig failureLimiterConfig = FailureLimiterConfig.defaultConfig(clusterUrls);
        FailureLimiter failureLimiter = new FailureLimiter(failureLimiterConfig);

        ShardingConfig shardingConfig = ShardingConfig.defaultConfig(clusterUrls);
        ShardingAlgorithm shardingAlgorithm = new ConsistentHashing(shardingConfig, failureLimiter);

        for (int i = 0; i < clusterSize; i++) {
            int flushThresholdBytes = 1 << 20; // 1 MB
            Path profilingDataPath = Path.of(
                    "/Users/valentinpronin/IdeaProjects/2024-highload-dht/"
                            + "src/main/java/ru/vk/itmo/test/"
                            + "proninvalentin/server_profiling_data" + ports[i]);
            Files.createDirectories(profilingDataPath);

            Config daoConfig = new Config(profilingDataPath, flushThresholdBytes);
            ReferenceDao referenceDao = new ru.vk.itmo.test.proninvalentin.dao.ReferenceDao(daoConfig);

            ServiceConfig serviceConfig = new ServiceConfig(ports[i], clusterUrls.get(i), clusterUrls,
                    profilingDataPath);
            Server server = new Server(serviceConfig, referenceDao, workerPool, shardingAlgorithm,
                    ServerConfig.defaultConfig(), failureLimiter);
            server.start();
            logger.info("[" + clusterUrls.get(i) + "] successfully started");
        }
    }
}
