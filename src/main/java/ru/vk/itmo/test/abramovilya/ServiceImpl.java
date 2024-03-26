package ru.vk.itmo.test.abramovilya;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.abramovilya.dao.DaoFactory;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {
    public static final int FLUSH_THRESHOLD_BYTES = 1024 * 1024;
    private Server server;
    private final ServiceConfig serviceConfig;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;

    public ServiceImpl(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = DaoFactory.createDao(new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES));
        server = new Server(serviceConfig, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 3)
    public static class Factory implements ServiceFactory.Factory {
        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
