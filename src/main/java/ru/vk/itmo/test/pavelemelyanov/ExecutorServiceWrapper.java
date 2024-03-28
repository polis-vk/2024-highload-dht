package ru.vk.itmo.test.pavelemelyanov;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static ru.vk.itmo.test.pavelemelyanov.ExecutorServiceConfig.KEEP_ALIVE_TIME;

public class ExecutorServiceWrapper {
    private final ExecutorService workingPool;
    private static final Logger log = LoggerFactory.getLogger(MyServer.class);

    public ExecutorServiceWrapper() {
        workingPool = new ThreadPoolExecutor(
                ExecutorServiceConfig.CORE_POOL_SIZE,
                ExecutorServiceConfig.MAX_CORE_POOL_SIZE,
                KEEP_ALIVE_TIME,
                TimeUnit.MILLISECONDS,
                ExecutorServiceConfig.queue,
                ExecutorServiceConfig.HANDLER
        );
    }

    public ExecutorService getExecutorService() {
        return workingPool;
    }

    public void shutdownAndAwaitTermination() {
        workingPool.shutdown();
        try {
            if (!workingPool.awaitTermination(60, TimeUnit.SECONDS)) {
                workingPool.shutdownNow();
                if (!workingPool.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("ExecutorService error with stopping");
                }
            }
        } catch (InterruptedException ex) {
            workingPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
