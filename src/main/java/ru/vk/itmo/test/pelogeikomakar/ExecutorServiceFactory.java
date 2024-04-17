package ru.vk.itmo.test.pelogeikomakar;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorServiceFactory {
    private static final int CORE_POOL_SIZE = 6;

    private static final long KEEP_ALIVE_TIME_SEC = 2;
    private static final TimeUnit UNIT = TimeUnit.SECONDS;

    private static final int QUEUE_CAPACITY = 128;

    private ExecutorServiceFactory() {
        throw new UnsupportedOperationException("cannot be instantiated");
    }

    public static ExecutorService newExecutorService(String threadPrefix) {
        return newExecutorService(threadPrefix, QUEUE_CAPACITY, CORE_POOL_SIZE);
    }

    public static ExecutorService newExecutorService(String threadPrefix,
                                                     int queuCapasity, int CorePoolSize) {
        BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(queuCapasity);
        ThreadPoolExecutor tpe = new ThreadPoolExecutor(CorePoolSize, CorePoolSize,
                KEEP_ALIVE_TIME_SEC, UNIT, queue,
                new ThreadFactory() {
                    private final AtomicInteger id = new AtomicInteger(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, threadPrefix + id.incrementAndGet());
                    }
                }, new ThreadPoolExecutor.AbortPolicy());
        tpe.prestartAllCoreThreads();
        return tpe;
    }
}
