package ru.vk.itmo.test.georgiidalbeev;

import one.nio.async.CustomThreadFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.georgiidalbeev.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class NewService implements Service {

    private static final long FLUSH_THRESHOLD = 512 * 1024;
    private static final int THREADS = 16;
    private static final int KEEP_ALIVE_TIME_SECONDS = 60;
    private static final int AWAIT_TERMINATION_SECONDS = 60;
    private static final int QUEUE_SIZE = 1000;
    private volatile boolean isStopped;
    private final ServiceConfig config;
    private NewServer server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ExecutorService executorService;
    private List<HttpClient> httpClients;

    public NewService(ServiceConfig config) {
        this.config = config;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(config.workingDir(), FLUSH_THRESHOLD));
        executorService = createPool();

        httpClients = config.clusterUrls().stream()
                .map(url -> HttpClient.newHttpClient())
                .collect(Collectors.toList());

        server = new NewServer(config, dao, executorService, config.clusterUrls(), httpClients);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        if (!isStopped) {
            server.stop();
            isStopped = true;
        }
        shutdownExecutorService();

        for (HttpClient httpClient : httpClients) {
            httpClient.close();
        }

        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    private void shutdownExecutorService() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(AWAIT_TERMINATION_SECONDS, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                THREADS,
                THREADS,
                KEEP_ALIVE_TIME_SECONDS,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                new CustomThreadFactory("PoolThread", true),
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.prestartAllCoreThreads();
        return pool;
    }
}
