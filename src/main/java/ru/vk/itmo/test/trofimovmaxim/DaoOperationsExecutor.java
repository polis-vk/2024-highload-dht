package ru.vk.itmo.test.trofimovmaxim;

import java.util.concurrent.*;

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
        executorService.close();
    }
}
