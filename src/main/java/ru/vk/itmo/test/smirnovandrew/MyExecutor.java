package ru.vk.itmo.test.smirnovandrew;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyExecutor extends ThreadPoolExecutor {

    private static final int KEEP_ALIVE_TIME = 30;

    private static final int QUEUE_CAPACITY = 1000;

    public MyExecutor(int corePoolSize, int maximumPoolSize) {
        super(
                corePoolSize,
                maximumPoolSize,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY)
        );
    }

    @Override
    public void shutdown() {
        super.shutdown();
        try {
            super.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        super.shutdownNow();
        try {
            super.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
