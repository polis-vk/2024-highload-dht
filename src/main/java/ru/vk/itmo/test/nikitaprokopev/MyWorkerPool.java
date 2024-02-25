package ru.vk.itmo.test.nikitaprokopev;

import one.nio.server.PayloadThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MyWorkerPool extends ThreadPoolExecutor implements ThreadFactory, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(MyWorkerPool.class);

    private final AtomicInteger index;
    private static final int MAX_QUEUE_LENGTH = 1000;
    private static final int MAX_THREADS = 8;

    public MyWorkerPool() {
        super(
                MAX_THREADS,
                MAX_THREADS,
                10,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE_LENGTH),
                new ThreadPoolExecutor.DiscardPolicy()
        );
        setThreadFactory(this);
        this.index = new AtomicInteger();
    }

    void gracefulShutdown() {
        log.info("Shutting down the worker pool");
        shutdown();
        try {
            if (!awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Forcing shutdown of the worker pool");
                shutdownNow();
            }
        } catch (InterruptedException e) {
            log.warn("Forcing shutdown of the worker pool");
            shutdownNow();
        }
    }

    @Override
    public Thread newThread(Runnable runnable) {
        PayloadThread thread = new PayloadThread(runnable, STR."MyWorkerPool-\{index.getAndIncrement()}");
        thread.setUncaughtExceptionHandler(this);
        return thread;
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        log.error(STR."Uncaught exception in thread \{thread.getName()}", throwable);
    }
}
