package ru.vk.itmo.test.proninvalentin;

import one.nio.server.PayloadThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class WorkerPool extends ThreadPoolExecutor implements ThreadFactory, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final AtomicInteger index;

    public WorkerPool(WorkerPoolConfig config) {
        super(config.corePoolSize, config.maxPoolSize, config.keepAliveTime, config.timeUnit,
                config.workQueue, config.threadFactory, config.rejectedExecutionHandler);
        this.index = new AtomicInteger();
    }

    void gracefulShutdown() {
        shutdown();
        try {
            awaitTermination(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        shutdownNow();
        try {
            awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public Thread newThread(Runnable r) {
        PayloadThread thread = new PayloadThread(r, "NIO Worker #" + index.incrementAndGet());
        thread.setUncaughtExceptionHandler(this);
        return thread;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Uncaught exception in {}", t, e);
    }
}
