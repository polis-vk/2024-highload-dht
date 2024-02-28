package ru.vk.itmo.test.tuzikovalexandr;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

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
}
