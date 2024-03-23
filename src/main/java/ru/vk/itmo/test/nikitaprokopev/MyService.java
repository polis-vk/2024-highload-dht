package ru.vk.itmo.test.nikitaprokopev;

import one.nio.async.CustomThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.Service;
import ru.vk.itmo.ServiceConfig;
import ru.vk.itmo.test.nikitaprokopev.dao.Config;
import ru.vk.itmo.test.nikitaprokopev.dao.Dao;
import ru.vk.itmo.test.nikitaprokopev.dao.Entry;
import ru.vk.itmo.test.nikitaprokopev.dao.ReferenceDao;

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

public class MyService implements Service {
    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1 MB
    private static final int MAX_QUEUE_LENGTH = 1000;
    private static final int MAX_THREADS = 8;
    private static final int KEEP_ALIVE_TIME = 10;
    private static final int AWAIT_TERMINATION_TIMEOUT = 30;
    private final Logger log = LoggerFactory.getLogger(MyService.class);
    private final ServiceConfig serviceConfig;
    private MyServer server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ThreadPoolExecutor workerPool;
    private HttpClient httpClient;
    private boolean stopped;

    public MyService(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public synchronized CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES));
        workerPool = createPool();
        httpClient = HttpClient.newBuilder()
                .executor(
                        Executors.newFixedThreadPool(
                                MAX_THREADS,
                                new CustomThreadFactory("CustomHttpClient")
                        )
                )
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        server = new MyServer(serviceConfig, dao, workerPool, httpClient);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> stop() throws IOException {
        if (stopped) {
            return CompletableFuture.completedFuture(null);
        }
        server.stop();
        shutdownAndAwaitTermination(workerPool);
        httpClient.close();
        dao.close();
        stopped = true;
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
