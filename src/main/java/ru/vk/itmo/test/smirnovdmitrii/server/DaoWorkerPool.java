package ru.vk.itmo.test.smirnovdmitrii.server;

import one.nio.server.PayloadThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class DaoWorkerPool extends ThreadPoolExecutor implements ThreadFactory, Thread.UncaughtExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(DaoWorkerPool.class);
    private final AtomicInteger index;

    public DaoWorkerPool(
            final int corePoolSize,
            final int maximumPoolSize,
            final long keepAliveTime,
            final TimeUnit unit,
            final BlockingQueue<Runnable> workQueue
    ) {

        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue);
        this.index = new AtomicInteger();
    }

    @Override
    public void uncaughtException(final Thread t, final Throwable e) {
        logger.error("Uncaught exception in {}", t, e);
    }

    @Override
    public Thread newThread(final Runnable r) {
        PayloadThread thread = new PayloadThread(r, "DAO Worker #" + index.incrementAndGet());
        thread.setUncaughtExceptionHandler(this);
        return thread;
    }
}
