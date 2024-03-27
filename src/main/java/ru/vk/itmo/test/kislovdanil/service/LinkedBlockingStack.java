package ru.vk.itmo.test.kislovdanil.service;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class LinkedBlockingStack<T> extends LinkedBlockingDeque<T> implements BlockingQueue<T> {
    @Override
    public void put(T t) throws InterruptedException {
        super.putFirst(t);
    }

    @Override
    public boolean offer(T t) {
        return super.offerFirst(t);
    }

    @Override
    public boolean offer(T t, long timeout, TimeUnit unit) throws InterruptedException {
        return super.offerFirst(t, timeout, unit);
    }

    @Override
    public boolean add(T t) {
        super.addFirst(t);
        return true;
    }
}
