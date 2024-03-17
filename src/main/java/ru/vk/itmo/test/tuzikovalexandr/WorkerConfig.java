package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.async.CustomThreadFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class WorkerConfig {
    private static final int MAX_QUEUE_SIZE = 1000;
    private final int corePoolSize;
    private final int maximumPoolSize;
    private final long keepAliveTime;
    private final TimeUnit unit;
    private final BlockingQueue<Runnable> workQueue;
    private final ThreadFactory threadFactory;
    private final RejectedExecutionHandler handler;

    public WorkerConfig() {
        this.corePoolSize = Runtime.getRuntime().availableProcessors();
        this.maximumPoolSize = Runtime.getRuntime().availableProcessors();
        this.keepAliveTime = 30L;
        this.unit = TimeUnit.SECONDS;
        this.workQueue = new LinkedBlockingDeque<>(MAX_QUEUE_SIZE);
        this.threadFactory = new CustomThreadFactory("w", true);
        this.handler = new ThreadPoolExecutor.AbortPolicy();
    }

    public WorkerConfig(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit unit,
                        BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory,
                        RejectedExecutionHandler handler) {
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.unit = unit;
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.handler = handler;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public long getKeepAliveTime() {
        return keepAliveTime;
    }

    public TimeUnit getUnit() {
        return unit;
    }

    public BlockingQueue<Runnable> getWorkQueue() {
        return workQueue;
    }

    public ThreadFactory getThreadFactory() {
        return threadFactory;
    }

    public RejectedExecutionHandler getHandler() {
        return handler;
    }
}
