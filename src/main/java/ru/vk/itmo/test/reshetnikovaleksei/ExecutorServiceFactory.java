package ru.vk.itmo.test.reshetnikovaleksei;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorServiceFactory {
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAXIMUM_POOL_SIZE = 20;
    private static final long KEEP_ALIVE_TIME = 60;
    private static final int QUEUE_CAPACITY = 100;
    private static final BlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final TimeUnit UNIT = TimeUnit.SECONDS;

    private ExecutorServiceFactory() {

    }

    public static ExecutorService createExecutorService() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_TIME, UNIT, QUEUE, new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
