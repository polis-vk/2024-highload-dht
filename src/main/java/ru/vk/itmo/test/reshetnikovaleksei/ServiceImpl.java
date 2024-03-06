package ru.vk.itmo.test.reshetnikovaleksei;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

public class ServiceImpl implements Service {
    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1mb

    private final ServiceConfig serviceConfig;
    private final Config daoConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private HttpServerImpl server;
    private ExecutorService executorService;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.daoConfig = createDaoConfig(serviceConfig);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        executorService = ExecutorServiceFactory.createExecutorService();
        server = new HttpServerImpl(serviceConfig, dao, executorService);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
        executorService.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 2)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }

    private static Config createDaoConfig(ServiceConfig serviceConfig) {
        return new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES);
    }
}
