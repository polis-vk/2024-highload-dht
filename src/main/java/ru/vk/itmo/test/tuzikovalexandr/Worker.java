package ru.vk.itmo.test.tuzikovalexandr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Worker {

    private final ExecutorService executorService;
    private static final Logger log = LoggerFactory.getLogger(ServerImpl.class);

    public Worker(WorkerConfig config) {
        executorService = new ThreadPoolExecutor(config.getCorePoolSize(), config.getMaximumPoolSize(),
                config.getKeepAliveTime(), config.getUnit(), config.getWorkQueue(), config.getThreadFactory(),
                config.getHandler());
    }

    public ExecutorService getExecutorService() {
        return executorService;
    }

    public void shutdownAndAwaitTermination() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    log.error("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
