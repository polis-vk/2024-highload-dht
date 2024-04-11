package ru.vk.itmo.test.reshetnikovaleksei;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class CustomThreadFactory implements ThreadFactory {
    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    private final String threadNamePrefix;

    public CustomThreadFactory(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, threadNamePrefix + THREAD_COUNTER.getAndIncrement());
    }
}
