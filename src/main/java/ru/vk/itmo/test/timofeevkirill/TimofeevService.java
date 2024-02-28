package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static ru.vk.itmo.test.timofeevkirill.Settings.FLUSH_THRESHOLD_BYTES;
import static ru.vk.itmo.test.timofeevkirill.Settings.getDefaultThreadPoolExecutor;

public class TimofeevService implements Service {

    private final ServiceConfig config;
    private final Config daoConfig;
    private TimofeevServer server;
    private ThreadPoolExecutor threadPoolExecutor;
    private ReferenceDao dao;

    public TimofeevService(ServiceConfig serviceConfig) {
        this.config = serviceConfig;
        this.daoConfig = new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES);
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        threadPoolExecutor = getDefaultThreadPoolExecutor();
        server = new TimofeevServer(config, dao, threadPoolExecutor);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        dao.close();
        threadPoolExecutor.close();
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig serviceConfig) {
            return new TimofeevService(serviceConfig);
        }
    }
}
