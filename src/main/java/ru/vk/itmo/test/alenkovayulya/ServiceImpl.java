package ru.vk.itmo.test.alenkovayulya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.alenkovayulya.dao.Dao;
import ru.vk.itmo.test.alenkovayulya.dao.EntryWithTimestamp;
import ru.vk.itmo.test.alenkovayulya.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ServiceImpl implements Service {

    private Dao<MemorySegment, EntryWithTimestamp<MemorySegment>> referenceDao;
    private ExecutorService executorService;
    private ServerImpl server;
    private final ServiceConfig config;
    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        executorService = ExecutorServiceFactory.getExecutorService(ExecutorServiceConfig.defaultConfig());
        referenceDao = new ReferenceDao(new Config(config.workingDir(), 1024 * 1024 * 1024));
        var shardSelector = new ShardSelector(config.clusterUrls());
        server = new ServerImpl(config, referenceDao, executorService, shardSelector);
        server.start();
        stopFlag.set(false);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopFlag.getAndSet(true)) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        shutdownExecutorService();
        shutdownDao();
        return CompletableFuture.completedFuture(null);
    }

    private void shutdownExecutorService() {
        executorService.shutdown();
        try {
            executorService.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executorService.shutdownNow();
        try {
            executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void shutdownDao() {
        try {
            referenceDao.close();
        } catch (IOException e) {
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
