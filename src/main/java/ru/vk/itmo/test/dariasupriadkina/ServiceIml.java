package ru.vk.itmo.test.dariasupriadkina;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.dariasupriadkina.dao.ReferenceDao;
import ru.vk.itmo.test.dariasupriadkina.sharding.ShardingPolicy;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerConfig;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerThreadPoolExecutor;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceIml implements Service {

    private Server server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private final Config daoConfig;
    private final ServiceConfig serviceConfig;
    private final WorkerConfig workerConfig;
    private WorkerThreadPoolExecutor workerThreadPoolExecutor;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ShardingPolicy shardingPolicy;

    public ServiceIml(ServiceConfig serviceConfig, Config daoConfig, WorkerConfig workerConfig,
                      ShardingPolicy shardingPolicy) {
        this.daoConfig = daoConfig;
        this.serviceConfig = serviceConfig;
        this.workerConfig = workerConfig;
        this.shardingPolicy = shardingPolicy;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        workerThreadPoolExecutor = new WorkerThreadPoolExecutor(workerConfig);
        workerThreadPoolExecutor.prestartAllCoreThreads();

        server = new Server(serviceConfig, dao, workerThreadPoolExecutor, shardingPolicy);
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
        dao.close();

        return CompletableFuture.completedFuture(null);
    }
}
