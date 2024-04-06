package ru.vk.itmo.test.tyapuevdmitrij;

import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.tyapuevdmitrij.dao.DAOException;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServiceImplementation implements ru.vk.itmo.Service {

    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20; // 1 MB
    private final ServiceConfig config;
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

    @ServiceFactory(stage = 2)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new ServiceImplementation(config);
        }
    }

    public static void main(String[] args) throws IOException {
        Path tempPath = new File("/home/dmitrij/Документы/JavaProjects/DaoServerData/").toPath();
        ServerImplementation server = new ServerImplementation(new ServiceConfig(8080,
                "http://localhost",
                List.of("http://localhost"),
                tempPath), new MemorySegmentDao(new Config(tempPath, FLUSH_THRESHOLD_BYTES)));
        server.start();
    }
}
