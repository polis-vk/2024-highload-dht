package ru.vk.itmo.test.emelyanovvitaliy;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

// Small experiment to implement LIFO queue, which randomly takes oldest element from the queue when it is too big
// Inspired by switching WRED algorithm
public class REDLinkedBlockingQueue<T> extends LinkedBlockingDeque<T> implements BlockingQueue<T> {
    // constants for linear congruential method
    private static final long A = Integer.MAX_VALUE - 7;
    private static final long C = Integer.MAX_VALUE >>> 1;
    private static final long M = Integer.MAX_VALUE - 3;
    private long randomValue = (long) (2 * (Math.random() - 0.5) * Long.MAX_VALUE);

    public REDLinkedBlockingQueue(int capacity) {
        super(capacity);
    }

    @Override
    public T remove() {
        if (shouldTakeLast()) {
            return removeLast();
        } else {
            return removeFirst();
        }
    }

    @Override
    public T poll() {
        if (shouldTakeLast()) {
            return pollLast();
        } else {
            return pollFirst();
        }
    }

    @Override
    public T element() {
        if (shouldTakeLast()) {
            return getLast();
        } else {
            return getFirst();
        }
    }

    @Override
    public T peek() {
        if (shouldTakeLast()) {
            return peekLast();
        } else {
            return peekFirst();
        }
    }

    @Override
    public T take() throws InterruptedException {
        if (shouldTakeLast()) {
            return takeLast();
        } else {
            return takeFirst();
        }
    }

    @Override
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        if (shouldTakeLast()) {
            return pollLast(timeout, unit);
        } else {
            return pollFirst(timeout, unit);
        }
    }

    private boolean shouldTakeLast() {
        int remainingCapacity = remainingCapacity();
        int size = size();
        // if less than half of capacity exceeded then take the first element
        if (remainingCapacity > size) {
            return false;
        }
        randomValue = getNewRandomValue(randomValue);
        return Math.abs(randomValue) % size >= remainingCapacity;
    }

    private long getNewRandomValue(long previousValue) {
        return (previousValue * A + C) % M;
    }
}
