package ru.vk.itmo.test.dariasupriadkina;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.test.dariasupriadkina.workers.WorkerConfig;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class NodeThreadPoolExecutor extends ThreadPoolExecutor {

    private static final Logger logger = LoggerFactory.getLogger(NodeThreadPoolExecutor.class.getName());
    private final int shutdownTimeoutSec;

    public NodeThreadPoolExecutor(int corePoolSize, int maximumPoolSize, BlockingQueue<Runnable> workQueue,
                                  ThreadFactory threadFactory, RejectedExecutionHandler rejectedExecutionHandler,
                                  int shutdownTimeoutSec) {
        super(corePoolSize, maximumPoolSize, WorkerConfig.KEEP_ALIVE_TIME,
                WorkerConfig.KEEP_ALIVE_TIME_SECONDS, workQueue, threadFactory, rejectedExecutionHandler);
        this.shutdownTimeoutSec = shutdownTimeoutSec;
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
}
