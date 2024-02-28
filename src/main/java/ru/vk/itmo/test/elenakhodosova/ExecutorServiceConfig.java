package ru.vk.itmo.test.elenakhodosova;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceConfig {

    private static final int POOL_SIZE = 64;
    private static final int MAX_POOL_SIZE = 128;
    private static final int QUEUE_SIZE = 256;

    private ExecutorServiceConfig() {
    }

    public static ExecutorService getExecutorService() {
        return new ThreadPoolExecutor(POOL_SIZE, MAX_POOL_SIZE,
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_SIZE));
    }
}
