package ru.vk.itmo.test.tuzikovalexandr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class Worker {

    private final ExecutorService executorService;

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
                    System.err.println("Pool did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
