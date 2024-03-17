package ru.vk.itmo.test.reshetnikovaleksei;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class CustomThreadFactory implements ThreadFactory {
    private static final String THREAD_NAME_PREFIX = "mega-thread-";
    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    @Override
    public Thread newThread(Runnable r) {
        return new Thread(r, THREAD_NAME_PREFIX + THREAD_COUNTER.getAndIncrement());
    }
}
