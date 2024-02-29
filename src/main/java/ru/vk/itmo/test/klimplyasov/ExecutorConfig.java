package ru.vk.itmo.test.klimplyasov;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorConfig {
    private static final int CORE_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final int MAXIMUM_POOL_SIZE = 20;
    private static final int KEEP_ALIVE_TIME = 60;
    private static final int CAPACITY = 1000;

    private ExecutorConfig() {
    }

    public static ExecutorService getExecutorService() {
        return new ThreadPoolExecutor(
                CORE_POOL_SIZE,
                MAXIMUM_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(CAPACITY)
        );
    }
}
