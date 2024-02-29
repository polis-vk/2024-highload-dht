package ru.vk.itmo.test.osokindm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private static final int MEMORY_LIMIT_BYTES = 8 * 1024 * 1024;
    private static final Logger logger = LoggerFactory.getLogger(HttpServerImpl.class);
    private final ServiceConfig config;
    private DaoWrapper daoWrapper;
    private HttpServerImpl server;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            daoWrapper = new DaoWrapper(new Config(config.workingDir(), MEMORY_LIMIT_BYTES));
            server = new HttpServerImpl(config, daoWrapper);
            server.start();
        } catch (IOException e) {
            logger.error("Error occurred while starting the server");
            throw new IOException(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
        try {
            daoWrapper.stop();
        } catch (IOException e) {
            logger.error("Error occurred while closing database");
        }
        server.stop();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 1)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ServiceImpl(config);
        }
    }
}
