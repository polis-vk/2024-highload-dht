package ru.vk.itmo.test.chebotinalexandr;

import one.nio.async.CustomThreadFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.chebotinalexandr.dao.NotOnlyInMemoryDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class StorageService implements Service {
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private StorageServer server;
    private ExecutorService executor;
    private static final int POOL_SIZE = 20;
    private static final int QUEUE_CAPACITY = 256;
    private static final long FLUSH_THRESHOLD_BYTES = 4_194_304L;
    private final ServiceConfig config;

    public StorageService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        this.dao = new NotOnlyInMemoryDao(new Config(config.workingDir(), FLUSH_THRESHOLD_BYTES));
        this.executor = new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY)
        );
        HttpClient httpClient = HttpClient.newBuilder()
                .executor(
                        Executors.newFixedThreadPool(
                                POOL_SIZE,
                                new CustomThreadFactory("httpClient")
                        )
                )
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        this.server = new StorageServer(config, dao, executor, httpClient);
        server.start();

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        waitForShutdown();
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    public void waitForShutdown() {
        executor.shutdown();

        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new InterruptedException("Timeout");
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @ServiceFactory(stage = 4)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new StorageService(config);
        }
    }

}
