package ru.vk.itmo.test.osokindm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.ServiceFactory;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class ServiceImpl implements Service {

    private final ServiceConfig config;
    private final Logger logger;
    private HttpServerImpl server;

    public ServiceImpl(ServiceConfig config) {
        this.config = config;
        this.logger = LoggerFactory.getLogger(HttpServerImpl.class);
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        try {
            server = new HttpServerImpl(config);
            server.start();
        } catch (IOException e) {
            logger.error("Error occurred while starting the server");
            throw new IOException(e);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() {
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
