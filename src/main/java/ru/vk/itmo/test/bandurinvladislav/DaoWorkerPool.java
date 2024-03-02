package ru.vk.itmo.test.bandurinvladislav;

import one.nio.server.PayloadThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.bandurinvladislav.concurrent.DeadlineRunnable;
import ru.vk.itmo.test.bandurinvladislav.util.Constants;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

final class DaoWorkerPool extends ThreadPoolExecutor implements ThreadFactory, Thread.UncaughtExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(DaoWorkerPool.class);

    private final AtomicInteger index;

    DaoWorkerPool(int corePoolSize,
                  int maximumPoolSize,
                  long keepAliveTime,
                  TimeUnit unit) {
        super(corePoolSize, maximumPoolSize, keepAliveTime, unit, new DeadlineArrayBlockingQueue(Constants.QUEUE_SIZE));
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

    private static final class DeadlineArrayBlockingQueue extends ArrayBlockingQueue<Runnable> {

        public DeadlineArrayBlockingQueue(int capacity) {
            super(capacity);
        }

        @Override
        public boolean offer(Runnable r) {
            return super.offer(new DeadlineRunnable(r, System.currentTimeMillis()));
        }
    }
}
