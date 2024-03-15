package ru.vk.itmo.test.alenkovayulya;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorServiceFactory {
    private static final AtomicInteger counter = new AtomicInteger();

    private ExecutorServiceFactory() {

    }

    public static ExecutorService getExecutorService(ExecutorServiceConfig config) {

        return new ThreadPoolExecutor(config.poolSize, config.poolSize,
                config.keepAliveTimeSec, TimeUnit.SECONDS, config.blockingQueue,
                r -> {
                    Thread thread = new Thread(r, config.executorName + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, config.rejectedExecutionHandler);
    }

}
