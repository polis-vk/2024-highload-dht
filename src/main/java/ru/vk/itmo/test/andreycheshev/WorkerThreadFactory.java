package ru.vk.itmo.test.andreycheshev;

import java.util.concurrent.ThreadFactory;

public class WorkerThreadFactory implements ThreadFactory {
    private final String prefix;
    private int counter;

    public WorkerThreadFactory(String prefix) {
        this.prefix = prefix + "-";
    }

    @Override
    public Thread newThread(Runnable runnable) {
        return new Thread(runnable, prefix + counter++);
    }
}
