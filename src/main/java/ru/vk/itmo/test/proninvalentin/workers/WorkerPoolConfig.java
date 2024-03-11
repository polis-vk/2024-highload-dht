package ru.vk.itmo.test.proninvalentin.workers;

import one.nio.async.CustomThreadFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WorkerPoolConfig {
    private static final int MAX_QUEUE_SIZE = 256;
    public final int corePoolSize;
    public final int maxPoolSize;
    public final long keepAliveTime;
    public final TimeUnit timeUnit;
    public final BlockingQueue<Runnable> workQueue;
    public final ThreadFactory threadFactory;
    public final RejectedExecutionHandler rejectedExecutionHandler;

    public WorkerPoolConfig(int corePoolSize, int maximumPoolSize, long keepAliveTime, TimeUnit timeUnit,
                            BlockingQueue<Runnable> workQueue, ThreadFactory threadFactory,
                            RejectedExecutionHandler rejectedExecutionHandler) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.rejectedExecutionHandler = rejectedExecutionHandler;
    }

    public static WorkerPoolConfig defaultConfig() {
        int poolSize = Runtime.getRuntime().availableProcessors() * 2;
        return new WorkerPoolConfig(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(MAX_QUEUE_SIZE),
                new CustomThreadFactory("Custom worker", true),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }
}
