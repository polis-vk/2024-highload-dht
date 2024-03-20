package ru.vk.itmo.test.vadimershov;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class RequestThreadExecutor extends ThreadPoolExecutor {
    record Config(
            int corePoolSize,
            int maximumPoolSize,
            int keepAliveTime,
            TimeUnit unit,
            BlockingQueue<Runnable> workQueue
    ) {
        public Config() {
            this(Runtime.getRuntime().availableProcessors(),
                    Runtime.getRuntime().availableProcessors(),
                    30,
                    TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(100));
        }

    }

    public RequestThreadExecutor(Config config) {
        super(config.corePoolSize(),
                config.maximumPoolSize(),
                config.keepAliveTime(),
                config.unit(),
                config.workQueue());
    }

    @Override
    public void shutdown() {
        super.shutdown();
        try {
            super.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        super.shutdownNow();
        try {
            super.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
