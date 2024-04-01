package ru.vk.itmo.test.andreycheshev;

import java.util.concurrent.ThreadFactory;

public class WorkerThreadFactory implements ThreadFactory {
    private static final String PREFIX = "RequestProcessingWorkerThread-";
    private int counter;

    @Override
    public Thread newThread(Runnable runnable) {
        return new Thread(runnable, PREFIX + counter++);
    }
}
