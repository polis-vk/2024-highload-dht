package ru.vk.itmo.test.andreycheshev;

import java.util.concurrent.ThreadFactory;

public class WorkerThreadFactory implements ThreadFactory {
    private static final String prefix = "RequestProcessingWorkerThread-";
    private int counter = 0;

    @Override
    public Thread newThread(Runnable runnable) {
        return new Thread(runnable, prefix + counter++);
    }
}
