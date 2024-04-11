package ru.vk.itmo.test.pavelemelyanov;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorServiceConfig {
    public static final int CORE_AMOUNT = Runtime.getRuntime().availableProcessors();
    public static final int CORE_POOL_SIZE = CORE_AMOUNT / 2;
    public static final int MAX_CORE_POOL_SIZE = CORE_AMOUNT;
    public static final long KEEP_ALIVE_TIME = TimeUnit.SECONDS.toNanos(3);
    public static final int QUEUE_CAPACITY = 100;
    public static final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    public static final RejectedExecutionHandler HANDLER = new ThreadPoolExecutor.AbortPolicy();

    private ExecutorServiceConfig() {

    }
}
