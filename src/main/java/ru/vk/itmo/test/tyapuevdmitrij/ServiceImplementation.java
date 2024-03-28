package ru.vk.itmo.test.tyapuevdmitrij;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.tyapuevdmitrij.dao.DAOException;
import ru.vk.itmo.test.tyapuevdmitrij.dao.MemorySegmentDao;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ServiceImplementation implements ru.vk.itmo.Service {

    private static final long FLUSH_THRESHOLD_BYTES = 1 << 20; // 1 MB
    private static final Logger logger = LoggerFactory.getLogger(ServiceImplementation.class);
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

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public ru.vk.itmo.Service create(ServiceConfig config) {
            return new ServiceImplementation(config);
        }
    }

    public static void main(String[] args) throws IOException {
        int[] ports = new int[3];
        Path[] paths = new Path[ports.length];
        List<String> cluster = new ArrayList<>(ports.length);
        for (int i = 0; i < ports.length; i++) {
            ports[i] = i + 12353;
            paths[i] = new File("/home/dmitrij/Документы/JavaProjects/DaoServerData/" + ports[i] + '/')
                    .toPath();
            if (!Files.exists(paths[i])) {
                Files.createDirectory(paths[i]);
            }
            cluster.add("http://localhost:" + ports[i]);
        }

        for (int i = 0; i < ports.length; i++) {
            String url = cluster.get(i);
            ServiceConfig cfg = new ServiceConfig(
                    ports[i],
                    url,
                    cluster,
                    Files.createTempDirectory("server")
            );
            new ServerImplementation(cfg,
                    new MemorySegmentDao(new Config(paths[i],
                            FLUSH_THRESHOLD_BYTES))).start();
            logger.info("Socket is ready: " + url);
        }
    }
}
