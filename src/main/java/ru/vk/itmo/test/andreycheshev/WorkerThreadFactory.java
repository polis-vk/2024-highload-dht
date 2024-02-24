package ru.vk.itmo.test.andreycheshev;

import java.util.concurrent.ThreadFactory;

public class WorkerThreadFactory implements ThreadFactory {
    private int counter = 0;
    private static final String prefix = "RequestProcessorThread-";

    @Override
    public Thread newThread(Runnable runnable) {
        return new Thread(runnable, prefix + counter++);
    }
}
