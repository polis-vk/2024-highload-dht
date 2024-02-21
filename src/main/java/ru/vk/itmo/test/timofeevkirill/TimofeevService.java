package ru.vk.itmo.test.timofeevkirill;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.timofeevkirill.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static ru.vk.itmo.test.timofeevkirill.Settings.FLUSH_THRESHOLD_BYTES;

public class TimofeevService implements Service {

    private final ServiceConfig config;
    private final Config daoConfig;
    private TimofeevServer server;
    private ReferenceDao dao;

    public TimofeevService(ServiceConfig serviceConfig) {
        this.config = serviceConfig;
        this.daoConfig = new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(daoConfig);
        server = new TimofeevServer(config, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
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
