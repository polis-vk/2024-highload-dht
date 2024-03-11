package ru.vk.itmo.test.proninvalentin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.proninvalentin.sharding.ConsistentHashing;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingAlgorithm;
import ru.vk.itmo.test.proninvalentin.sharding.ShardingConfig;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPool;
import ru.vk.itmo.test.proninvalentin.workers.WorkerPoolConfig;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    private final Config daoConfig;
    private final ServiceConfig config;
    private final List<String> clusterUrls;
    private ReferenceDao dao;
    private Server server;
    private WorkerPool workerPool;
    private static final Logger logger = LoggerFactory.getLogger(ServiceImpl.class);
    private Boolean serverAlreadyClosed = false;

    public ServiceImpl(ServiceConfig config) throws IOException {
        int flushThresholdBytes = 1 << 20; // 1 MB
        this.config = config;
        this.daoConfig = new Config(config.workingDir(), flushThresholdBytes);
        this.clusterUrls = config.clusterUrls();
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        
        WorkerPoolConfig workerPoolConfig = WorkerPoolConfig.defaultConfig();
        workerPool = new WorkerPool(workerPoolConfig);

        ShardingConfig shardingConfig = ShardingConfig.defaultConfig(clusterUrls);
        ShardingAlgorithm shardingAlgorithm = new ConsistentHashing(shardingConfig);

        server = new Server(config, dao, workerPool, shardingAlgorithm, ServerConfig.defaultConfig());
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (serverAlreadyClosed) {
            logger.warn("Trying to close already closed server!");
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        workerPool.gracefulShutdown();
        dao.close();
        serverAlreadyClosed = true;
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            try {
                return new ServiceImpl(config);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
