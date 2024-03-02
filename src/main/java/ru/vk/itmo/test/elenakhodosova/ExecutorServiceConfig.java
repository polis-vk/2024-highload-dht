package ru.vk.itmo.test.elenakhodosova;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorServiceConfig {

    private static final int POOL_SIZE = 64;
    private static final int MAX_POOL_SIZE = 128;
    private static final int QUEUE_SIZE = 256;

    private ExecutorServiceConfig() {
    }

    public static ExecutorService getExecutorService() {
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r ->
                new Thread(r, "CustomWorkerThread: " + threadCounter.getAndIncrement());
        return new ThreadPoolExecutor(POOL_SIZE, MAX_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE), threadFactory);
    }
}
