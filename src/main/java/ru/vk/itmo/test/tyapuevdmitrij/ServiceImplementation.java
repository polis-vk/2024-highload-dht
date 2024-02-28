package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.tyapuevdmitrij.dao.DAOException;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImplementation implements ru.vk.itmo.Service {

    private final ServiceConfig config;
    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20; // 1 MB
    private ServerImplementation server;
    private MemorySegmentDao memorySegmentDao;

    public ServiceImplementation(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        memorySegmentDao = new MemorySegmentDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        try {
            server = new ServerImplementation(config, memorySegmentDao);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        try {
            memorySegmentDao.close();
        } catch (IOException e) {
            throw new DAOException("can't close DAO", e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new ServiceImplementation(config);
        }
    }
}
