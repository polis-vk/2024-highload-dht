package ru.vk.itmo.test.alenkovayulya;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

public class ExecutorServiceConfig {

    private static final int MAX_POOL_SIZE = 1000;
    private static final int MAX_QUEUE_SIZE = 1000;

    public final int poolSize;
    public final int queueSize;
    public final long keepAliveTimeSec;
    public final BlockingQueueType blockingQueueType;
    public final RejectedExecutionHandler rejectedExecutionHandler;
    public final String executorName;

    public ExecutorServiceConfig(int poolSize, int queueSize, long keepAliveTimeSec,
                                 BlockingQueueType blockingQueueType,
                                 RejectedExecutionHandler rejectedExecutionHandler, String executorName) {
        this.poolSize = validateValue(poolSize, MAX_POOL_SIZE);
        this.queueSize = validateValue(queueSize, MAX_QUEUE_SIZE);
        this.keepAliveTimeSec = keepAliveTimeSec;
        this.blockingQueueType = blockingQueueType;
        this.rejectedExecutionHandler = rejectedExecutionHandler;
        this.executorName = executorName;
    }

    private int validateValue(int value, int maxPossibleValue) {
        return (value == 0 || value > maxPossibleValue) ? maxPossibleValue : value;
    }

    public static ExecutorServiceConfig defaultConfig() {
        return new ExecutorServiceConfig(
                500, 100, 30,
                BlockingQueueType.ARRAY,
                new ThreadPoolExecutor.AbortPolicy(),
                "yulalenkExecutor");
    }
}
