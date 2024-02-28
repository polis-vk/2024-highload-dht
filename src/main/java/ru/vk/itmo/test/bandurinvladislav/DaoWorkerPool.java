package ru.vk.itmo.test.bandurinvladislav;

import one.nio.server.PayloadThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class DaoWorkerPool extends ThreadPoolExecutor implements ThreadFactory, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DaoWorkerPool.class);

    private final AtomicInteger index;

    DaoWorkerPool(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit,
                  BlockingQueue<Runnable> workQueue) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        setThreadFactory(this);
        this.index = new AtomicInteger();
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public void gracefulShutdown() {
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
        PayloadThread thread = new PayloadThread(r, "Dao Worker #" + index.incrementAndGet());
        thread.setUncaughtExceptionHandler(this);
        return thread;
    }

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        log.error("Uncaught exception in {}", t, e);
    }
}
