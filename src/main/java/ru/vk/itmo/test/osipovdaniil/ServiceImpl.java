package ru.vk.itmo.test.osipovdaniil;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.osipovdaniil.dao.ReferenceDao;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    public static final long FLUSH_THRESHOLD_BYTES = 1024L * 1024;
    private ServerImpl server;
    private final ServiceConfig serviceConfig;
    private ReferenceDao dao;

    private final Config daoConfig;

    public ServiceImpl(final ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.daoConfig = createDaoConfig(serviceConfig);
    }

    private static Config createDaoConfig(final ServiceConfig config) {
        return new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES);
    }


    @Override
    public CompletableFuture<Void> start() throws IOException {
        this.dao = new ReferenceDao(daoConfig);
        this.server = new ServerImpl(serviceConfig, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }
}
