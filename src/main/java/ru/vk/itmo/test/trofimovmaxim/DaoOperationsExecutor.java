package ru.vk.itmo.test.trofimovmaxim;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class DaoOperationsExecutor {
    private static final RejectedExecutionHandler ABORT_POLICY = new ThreadPoolExecutor.AbortPolicy();
    private ExecutorService executorService;

    public void run(Runnable operation) throws RejectedExecutionException {
        executorService.execute(operation);
    }

    public void start() {
        executorService = new ThreadPoolExecutor(ExecutorServiceSettings.CORE_POOL_SIZE,
                ExecutorServiceSettings.MAX_POOL_SIZE,
                ExecutorServiceSettings.TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(ExecutorServiceSettings.QUEUE_SIZE),
                ABORT_POLICY);
    }

    public void stop() {
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
