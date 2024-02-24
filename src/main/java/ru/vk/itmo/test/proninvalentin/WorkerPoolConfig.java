package ru.vk.itmo.test.proninvalentin;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WorkerPoolConfig {
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
        maxPoolSize = maximumPoolSize;
        this.keepAliveTime = keepAliveTime;
        this.timeUnit = timeUnit;
        this.workQueue = workQueue;
        this.threadFactory = threadFactory;
        this.rejectedExecutionHandler = rejectedExecutionHandler;
    }

    public static WorkerPoolConfig defaultConfig() {
        int maxQueueSize = 4200;
        return new WorkerPoolConfig(
                Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().availableProcessors(),
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(maxQueueSize),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardPolicy());
    }
}
