package ru.vk.itmo.test.smirnovandrew;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class MyExecutor extends ThreadPoolExecutor {

    private static final int KEEP_ALIVE_TIME = 30;

    private static final int QUEUE_CAPACITY = 1000;

    private static final int FIRST_TIMEOUT = 2;

    private static final int SECOND_TIMEOUT = 2;

    public MyExecutor(int corePoolSize, int maximumPoolSize) {
        super(
                corePoolSize,
                maximumPoolSize,
                KEEP_ALIVE_TIME,
                TimeUnit.SECONDS,
                new MyStack<>(QUEUE_CAPACITY)
        );
    }

    private static class MyStack<E> extends LinkedBlockingDeque<E> implements BlockingQueue<E> {

        public MyStack(int capacity) {
            super(capacity);
        }

        @Override
        public void put(E e) throws InterruptedException {
            super.putFirst(e);
        }

        @Override
        public boolean offer(E e) {
            return super.offerFirst(e);
        }

        @Override
        public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
            return super.offerFirst(e, timeout, unit);
        }

        @Override
        public boolean add(E e) {
            super.addFirst(e);
            return true;
        }
    }

    @Override
    public void shutdown() {
        super.shutdown();
        try {
            super.awaitTermination(FIRST_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        super.shutdownNow();
        try {
            super.awaitTermination(SECOND_TIMEOUT, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
