package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.test.dariasupriadkina.dao.ExtendedEntry;
import ru.vk.itmo.test.dariasupriadkina.dao.ReferenceDao;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.CustomThreadConfig;
import ru.vk.itmo.test.dariasupriadkina.workers.CustomThreadPoolExecutor;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceIml implements Service {

    private final Config daoConfig;
    private final ServiceConfig serviceConfig;
    private final CustomThreadConfig workerConfig;
    private final CustomThreadConfig nodeConfig;
    private final ShardingPolicy shardingPolicy;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Server server;
    private Dao<MemorySegment, ExtendedEntry<MemorySegment>> dao;
    private CustomThreadPoolExecutor workerThreadPoolExecutor;
    private CustomThreadPoolExecutor nodeThreadPoolExecutor;

    public ServiceIml(ServiceConfig serviceConfig, Config daoConfig,
                      CustomThreadConfig workerConfig, ShardingPolicy shardingPolicy,
                      CustomThreadConfig nodeConfig) {
        this.daoConfig = daoConfig;
        this.serviceConfig = serviceConfig;
        this.workerConfig = workerConfig;
        this.shardingPolicy = shardingPolicy;
        this.nodeConfig = nodeConfig;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        workerThreadPoolExecutor = new CustomThreadPoolExecutor(workerConfig);
        nodeThreadPoolExecutor = new CustomThreadPoolExecutor(nodeConfig);
        nodeThreadPoolExecutor.prestartAllCoreThreads();
        workerThreadPoolExecutor.prestartAllCoreThreads();

        server = new Server(serviceConfig, dao, workerThreadPoolExecutor, nodeThreadPoolExecutor, shardingPolicy);
        stopped.set(false);

        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopped.getAndSet(true)) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        workerThreadPoolExecutor.shutdownAndAwaitTermination();
        nodeThreadPoolExecutor.shutdownAndAwaitTermination();
        dao.close();

        return CompletableFuture.completedFuture(null);
    }
}
