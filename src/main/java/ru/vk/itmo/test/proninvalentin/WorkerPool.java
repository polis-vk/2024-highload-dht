package ru.vk.itmo.test.proninvalentin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class WorkerPool {
    private static final int SOFT_SHUT_DOWN_TIME = 20;
    private static final int HARD_SHUT_DOWN_TIME = 10;

    public final ExecutorService pool;

    public WorkerPool(WorkerPoolConfig config) {
        pool = new ThreadPoolExecutor(config.corePoolSize, config.maxPoolSize, config.keepAliveTime, config.timeUnit,
                config.workQueue, config.threadFactory, config.rejectedExecutionHandler);
    }

    void gracefulShutdown() {
        pool.shutdown();
        try {
            pool.awaitTermination(SOFT_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        pool.shutdownNow();
        try {
            pool.awaitTermination(HARD_SHUT_DOWN_TIME, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
