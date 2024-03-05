package ru.vk.itmo.test.dariasupriadkina.workers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class WorkerThreadPoolExecutor extends ThreadPoolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(WorkerThreadPoolExecutor.class.getName());
    private static final ThreadFactory threadFactory = new WorkerThreadFactory();
    private final int shutdownTimeoutSec;

    public WorkerThreadPoolExecutor(WorkerConfig workerConfig) {
        super(workerConfig.getCorePoolSize(), workerConfig.getMaximumPoolSize(),
                WorkerConfig.KEEP_ALIVE_TIME, WorkerConfig.KEEP_ALIVE_TIME_SECONDS,
                workerConfig.getWorkQueue(), threadFactory);
        this.shutdownTimeoutSec = workerConfig.getShutdownTimeoutSec();
    }

    //    Метод из документации на ExecutorService
    public void shutdownAndAwaitTermination() {
        this.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!this.awaitTermination(shutdownTimeoutSec, TimeUnit.SECONDS)) {
                    logger.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }

    private static class WorkerThreadFactory implements ThreadFactory {

        private static final String PREFIX = "worker-thread-";
        private static final AtomicLong THREAD_COUNTER = new AtomicLong(0L);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, PREFIX + THREAD_COUNTER.getAndIncrement());
        }

    }

}
