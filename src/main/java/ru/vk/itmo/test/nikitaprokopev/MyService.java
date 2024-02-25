package ru.vk.itmo.test.nikitaprokopev;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MyService implements Service {

    private static final long FLUSH_THRESHOLD_BYTES = 1024 * 1024; // 1 MB
    private static final int MAX_QUEUE_LENGTH = 1000;
    private static final int MAX_THREADS = 8;
    private static final int KEEP_ALIVE_TIME = 10;
    private static final int AWAIT_TERMINATION_TIMEOUT = 30;
    private final ServiceConfig serviceConfig;
    private MyServer server;
    private Dao<MemorySegment, Entry<MemorySegment>> dao;
    private ThreadPoolExecutor workerPool;

    public MyService(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }

    @Override
    public CompletableFuture<Void> start() throws IOException {
        dao = new ReferenceDao(new Config(serviceConfig.workingDir(), FLUSH_THRESHOLD_BYTES));
        workerPool = createPool();
        server = new MyServer(serviceConfig, dao, workerPool);
        server.start();
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> stop() throws InterruptedException, IOException {
        server.stop();
        dao.close();
        workerPool.shutdown();
        workerPool.awaitTermination(AWAIT_TERMINATION_TIMEOUT, TimeUnit.SECONDS);
        return CompletableFuture.completedFuture(null);
    }

    private ThreadPoolExecutor createPool() {
        return new ThreadPoolExecutor(
                MAX_THREADS,
                MAX_THREADS,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_LENGTH),
                new MyThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static class MyThreadFactory implements ThreadFactory {
        private static final Logger log = LoggerFactory.getLogger(MyThreadFactory.class);
        private final AtomicInteger index;

        public MyThreadFactory() {
            this.index = new AtomicInteger();
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "WorkerPoolThread-" + index.getAndIncrement());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((t, e) -> {
                log.error("Uncaught exception in thread " + t.getName(), e);
            });
            return thread;
        }
    }
}
