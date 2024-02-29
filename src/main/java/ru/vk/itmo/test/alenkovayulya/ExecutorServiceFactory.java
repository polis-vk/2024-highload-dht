package ru.vk.itmo.test.alenkovayulya;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ExecutorServiceFactory {
    private static final AtomicInteger counter = new AtomicInteger();

    public static ExecutorService getExecutorService(ExecutorServiceConfig config) {

        BlockingQueue<Runnable> queue =
                switch (config.blockingQueueType) {
                    case ARRAY -> new ArrayBlockingQueue<>(config.queueSize);
                    case LINKED -> new LinkedBlockingQueue<>(config.queueSize);
                    case SYNCHRONIZED -> new SynchronousQueue<>();
                };
        return new ThreadPoolExecutor(config.poolSize, config.poolSize,
                config.keepAliveTimeSec, TimeUnit.SECONDS, queue,
                r -> {
                    Thread thread = new Thread(r, config.executorName + "-" + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, config.rejectedExecutionHandler);
    }

}
