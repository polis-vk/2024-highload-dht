package ru.vk.itmo.test.pavelemelyanov;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public final class ExecutorServiceConfig {
    public static final int CORE_AMOUNT = Runtime.getRuntime().availableProcessors();
    public static final int CORE_POOL_SIZE = CORE_AMOUNT / 2;
    public static final int MAX_CORE_POOL_SIZE = CORE_AMOUNT;
    public static final int KEEP_ALIVE_TIME = 200;
    public static final int QUEUE_CAPACITY = 64;
    public static final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    public static final RejectedExecutionHandler HANDLER = new ThreadPoolExecutor.AbortPolicy();

    private ExecutorServiceConfig() {

    }
}
