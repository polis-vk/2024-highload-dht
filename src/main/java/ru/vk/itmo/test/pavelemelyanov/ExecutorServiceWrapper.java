package ru.vk.itmo.test.pavelemelyanov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ExecutorServiceWrapper {
    public static final int TERMINATION_TIMEOUT = 60;
    private static final Logger LOG = LoggerFactory.getLogger(MyServer.class);

    private final ExecutorService workingPool;

    public ExecutorServiceWrapper() {
        workingPool = new ThreadPoolExecutor(
                ExecutorServiceConfig.CORE_POOL_SIZE,
                ExecutorServiceConfig.MAX_CORE_POOL_SIZE,
                ExecutorServiceConfig.KEEP_ALIVE_TIME,
                ExecutorServiceConfig.UNIT,
                ExecutorServiceConfig.queue,
                ExecutorServiceConfig.threadFactory,
                ExecutorServiceConfig.HANDLER
        );
    }

    public ExecutorService getExecutorService() {
        return workingPool;
    }

    public void shutdownAndAwaitTermination() {
        workingPool.shutdown();
        try {
            if (!workingPool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                workingPool.shutdownNow();
                if (!workingPool.awaitTermination(TERMINATION_TIMEOUT, TimeUnit.SECONDS)) {
                    LOG.error("ExecutorService error with stopping");
                }
            }
        } catch (InterruptedException ex) {
            workingPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
