package ru.vk.itmo.test.khadyrovalmasgali.server;

import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class LinkedBlockingStack<E> extends LinkedBlockingDeque<E> {

    public LinkedBlockingStack(int capacity) {
        super(capacity);
    }

    @Override
    public E poll() {
        return pollLast();
    }

    @Override
    public E poll(long timeout, TimeUnit unit) throws InterruptedException {
        return pollLast(timeout, unit);
    }

    @Override
    public E take() throws InterruptedException {
        return takeLast();
    }
}
