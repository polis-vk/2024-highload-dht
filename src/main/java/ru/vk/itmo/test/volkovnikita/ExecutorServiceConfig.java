package ru.vk.itmo.test.volkovnikita;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorServiceConfig {

    private ExecutorServiceConfig() {

    }

    private static final Integer POOL_SIZE = 8;
    private static final Integer KEEP_ALIVE = 30;
    private static final Integer QUEUE_SIZE = 1500;

    public static ExecutorService newExecutorService() {
        AtomicInteger threadCounter = new AtomicInteger(0);
        ThreadFactory threadFactory = r ->
                new Thread(r, "workerThread: " + threadCounter.getAndIncrement());
        return new ThreadPoolExecutor(
                POOL_SIZE,
                POOL_SIZE,
                KEEP_ALIVE,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
