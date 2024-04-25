package ru.vk.itmo.test.reshetnikovaleksei;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Dao;
import ru.vk.itmo.test.reshetnikovaleksei.dao.Entry;
import ru.vk.itmo.test.reshetnikovaleksei.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ServiceImpl implements Service {
    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1mb
    private static final long TERMINATION_TIMEOUT_SECONDS = 60;

    private final ServiceConfig serviceConfig;
    private final Config daoConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private HttpServerImpl server;
    private ExecutorService executorService;
    private ExecutorService localExecutorService;
    private RequestRouter requestRouter;
    private boolean stopped;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.daoConfig = createDaoConfig(serviceConfig);
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        executorService = ExecutorServiceFactory.createExecutorService("main-thread-");
        localExecutorService = ExecutorServiceFactory.createExecutorService("local-thread-");
        requestRouter = new RequestRouter(serviceConfig);
        server = new HttpServerImpl(serviceConfig, dao, executorService, localExecutorService, requestRouter);
        server.start();
        stopped = false;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }

        try {
            server.stop();
            requestRouter.close();
            shutdownGracefully(executorService);
            shutdownGracefully(localExecutorService);
        } finally {
            dao.close();
        }

        stopped = true;
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 6)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

    private static Config createDaoConfig(ServiceConfig serviceConfig) {
        return new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES);
    }

    private void shutdownGracefully(ExecutorService executorService) {
        executorService.shutdown();

        try {
            if (!executorService.awaitTermination(TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new InterruptedException("timeout");
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        stopped = true;
    }
}
