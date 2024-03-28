package ru.vk.itmo.test.alenkovayulya;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class ExecutorServiceConfig {

    private static final int MAX_POOL_SIZE = 1000;
    private static final int MAX_QUEUE_SIZE = 1000;

    public final int poolSize;
    public final int queueSize;
    public final long keepAliveTimeSec;
    public final BlockingQueue<Runnable> blockingQueue;
    public final RejectedExecutionHandler rejectedExecutionHandler;
    public final String executorName;

    public ExecutorServiceConfig(int poolSize, int queueSize, long keepAliveTimeSec,
                                 BlockingQueue<Runnable> blockingQueue,
                                 RejectedExecutionHandler rejectedExecutionHandler, String executorName) {
        this.poolSize = validateValue(poolSize, MAX_POOL_SIZE);
        this.queueSize = validateValue(queueSize, MAX_QUEUE_SIZE);
        this.keepAliveTimeSec = keepAliveTimeSec;
        this.blockingQueue = blockingQueue;
        this.rejectedExecutionHandler = rejectedExecutionHandler;
        this.executorName = executorName;
    }

    private int validateValue(int value, int maxPossibleValue) {
        if (value == 0 || value > maxPossibleValue) {
            throw new IllegalArgumentException(
                    "The set value reaches the maximum or is equal to 0, maximum = " + maxPossibleValue);
        }
        return value;
    }

    public static ExecutorServiceConfig defaultConfig() {
        return new ExecutorServiceConfig(
                32, 128, 30,
                new ArrayBlockingQueue<>(128),
                new ThreadPoolExecutor.AbortPolicy(),
                "yulalenkExecutor");
    }
}
