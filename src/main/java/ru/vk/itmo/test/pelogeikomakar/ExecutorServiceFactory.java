package ru.vk.itmo.test.pelogeikomakar;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ExecutorServiceFactory {
    private static final int CORE_POOL_SIZE = 10;
    private static final int MAXIMUM_POOL_SIZE = 256;

    private static final long KEEP_ALIVE_TIME = 2;

    private static final int CAPACITY = 2048;

    private static final BlockingQueue<Runnable> QUEUE = new ArrayBlockingQueue<>(CAPACITY);

    private static final TimeUnit UNIT = TimeUnit.SECONDS;

    public static ExecutorService getExecutorService() {
        return new ThreadPoolExecutor(CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE_TIME, UNIT, QUEUE,
                new MyThreadFactory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public static final class MyThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ExecutorServiceThread");
        }
    }
}
