package ru.vk.itmo.test.dariasupriadkina.workers;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class WorkerThreadPoolExecutor extends ThreadPoolExecutor {
    public WorkerThreadPoolExecutor(WorkerConfig workerConfig) {
        super(workerConfig.getCorePoolSize(), workerConfig.getMaximumPoolSize(),
                WorkerConfig.KEEP_ALIVE_TIME, WorkerConfig.UNIT, workerConfig.getWorkQueue());
    }


    //    Метод из документации на ExecutorService
    public void shutdownAndAwaitTermination() {
        this.shutdown(); // Disable new tasks from being submitted
        try {
            // Wait a while for existing tasks to terminate
            if (!awaitTermination(5, TimeUnit.SECONDS)) {
                shutdownNow(); // Cancel currently executing tasks
                // Wait a while for tasks to respond to being cancelled
                if (!this.awaitTermination(5, TimeUnit.SECONDS))
                    System.err.println("Pool did not terminate");
            }
        } catch (InterruptedException ie) {
            // (Re-)Cancel if current thread also interrupted
            shutdownNow();
            // Preserve interrupt status
            Thread.currentThread().interrupt();
        }
    }
}
