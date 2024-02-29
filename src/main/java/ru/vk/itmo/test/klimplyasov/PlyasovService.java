package ru.vk.itmo.test.klimplyasov;

import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.klimplyasov.dao.ReferenceDao;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class PlyasovService implements Service {

    private PlyasovServer server;
    private ReferenceDao dao;
    private final ServiceConfig config;
    private ExecutorService executorService;

    public PlyasovService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), 48000));
        try {
            executorService = ExecutorConfig.getExecutorService();
            server = new PlyasovServer(config, dao, executorService);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        executorService.close();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    @ServiceFactory(stage = 2)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new PlyasovService(config);
        }
    }
}
