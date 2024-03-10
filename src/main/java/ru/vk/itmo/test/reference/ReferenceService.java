package ru.vk.itmo.test.reference;

import one.nio.async.CustomThreadFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.ServiceFactory;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;

public class ReferenceService implements Service {

    private static final long FLUSHING_THRESHOLD_BYTES = 1024 * 1024;
    private static final int THREADS = Runtime.getRuntime().availableProcessors();
    private static final int QUEUE_SIZE = 1024;

    private final ServiceConfig config;
    private ExecutorService executor;

    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ReferenceServer server;

    public ReferenceService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSHING_THRESHOLD_BYTES));
        ArrayBlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_SIZE);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(THREADS, THREADS,
            1000, TimeUnit.SECONDS,
            queue,
            new CustomThreadFactory("worker", true),
            new ThreadPoolExecutor.AbortPolicy());
        executor.prestartAllCoreThreads();
        this.executor = executor;
        server = new ReferenceServer(config, executor, dao);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        shutdownAndAwaitTermination(executor);
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    private static void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
           if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
               pool.shutdownNow();
               if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                   System.err.println("Pool did not terminate");
               }
           }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    @ServiceFactory(stage = 2)
    public static class Factory implements ServiceFactory.Factory {

        @Override
        public Service create(ServiceConfig config) {
            return new ReferenceService(config);
        }
    }

    public static void main(String[] args) throws IOException {
        ReferenceService service = new ReferenceService(
            new ServiceConfig(8080, "http://localhost",
            List.of("http://localhost"),
            Paths.get("tmp/db"))
        );
        try {
            service.start().get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }
}
