package ru.vk.itmo.test.nikitaprokopev;

import one.nio.async.CustomThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.dao.Config;
import ru.vk.itmo.dao.Dao;
import ru.vk.itmo.dao.Entry;
import ru.vk.itmo.test.reference.dao.ReferenceDao;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.http.HttpClient;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyService implements Service {
    private final Logger log = LoggerFactory.getLogger(MyService.class);
    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1 MB
    private static final int MAX_QUEUE_LENGTH = 1000;
    private static final int MAX_THREADS = 8;
    private static final int KEEP_ALIVE_TIME = 10;
    private static final int AWAIT_TERMINATION_TIMEOUT = 30;
    private final ServiceConfig serviceConfig;
    private MyServer server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ThreadPoolExecutor workerPool;
    private HttpClient[] httpClients;

    public MyService(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES));
        workerPool = createPool();
        httpClients = new HttpClient[serviceConfig.clusterUrls().size() - 1];
        for (int i = 0; i < serviceConfig.clusterUrls().size() - 1; i++) {
            httpClients[i] = HttpClient.newHttpClient();
        }
        int node_id = serviceConfig.clusterUrls().indexOf(serviceConfig.selfUrl());
        if (node_id == -1) {
            log.error("Node id not found in cluster urls");
            return CompletableFuture.completedFuture(null);
        }
        server = new MyServer(serviceConfig, dao, workerPool, httpClients, node_id);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws IOException {
        server.stop();
        shutdownAndAwaitTermination(workerPool);
        for (HttpClient httpClient : httpClients) {
            httpClient.close();
        }
        dao.close();
        return CompletableFuture.completedFuture(null);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                pool.shutdownNow();
                if (!pool.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ThreadPoolExecutor createPool() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                MAX_THREADS,
                MAX_THREADS,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_LENGTH),
                new CustomThreadFactory("WorkerPoolThread", true),
                new ThreadPoolExecutor.AbortPolicy()
        );
        pool.prestartAllCoreThreads();
        return pool;
    }
}
