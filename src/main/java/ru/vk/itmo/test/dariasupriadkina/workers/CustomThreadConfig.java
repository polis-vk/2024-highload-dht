package ru.vk.itmo.test.dariasupriadkina.workers;

import one.nio.async.CustomThreadFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CustomThreadConfig {

    public static final long KEEP_ALIVE_TIME = 1000L;
    public static final TimeUnit KEEP_ALIVE_TIME_SECONDS = TimeUnit.SECONDS;
    public static final int QUEUE_SIZE = 1024;
    public static final int THREADS = Runtime.getRuntime().availableProcessors();
    public static final int SHUTDOWN_TIMEOUT_SEC = 60;

    private final int corePoolSize;
    private final int maximumPoolSize;
    private final int shutdownTimeoutSec;
    private final ArrayBlockingQueue<Runnable> workQueue;
    private final CustomThreadFactory threadFactory;
    private final RejectedExecutionHandler handler;

    public CustomThreadConfig(int corePoolSize, int maximumPoolSize, int queueSize, int shutdownTimeoutSec,
                              String threadName, RejectedExecutionHandler handler) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.workQueue = new ArrayBlockingQueue<>(queueSize);
        this.shutdownTimeoutSec = shutdownTimeoutSec;
        this.threadFactory = new CustomThreadFactory(threadName, true);
        this.handler = handler;
    }

    public static CustomThreadConfig baseConfig(String threadName) {
        return new CustomThreadConfig(THREADS * 2, THREADS * 2,
                QUEUE_SIZE, SHUTDOWN_TIMEOUT_SEC, threadName, new ThreadPoolExecutor.AbortPolicy());
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public BlockingQueue<Runnable> getWorkQueue() {
        return workQueue;
    }

    public int getShutdownTimeoutSec() {
        return shutdownTimeoutSec;
    }

    public CustomThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public RejectedExecutionHandler getHandler() {
        return handler;
    }
}
