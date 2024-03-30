package ru.vk.itmo.test.proninvalentin.workers;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

public final class WorkerPool {
    public final ExecutorService pool;

    public WorkerPool(WorkerPoolConfig config) {
        ThreadPoolExecutor preStartedPool = new ThreadPoolExecutor(config.corePoolSize, config.maxPoolSize,
                config.keepAliveTime, config.timeUnit, config.workQueue, config.threadFactory,
                config.rejectedExecutionHandler);
        preStartedPool.prestartAllCoreThreads();
        pool = preStartedPool;
    }
}
