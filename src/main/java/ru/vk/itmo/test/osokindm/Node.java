package ru.vk.itmo.test.osokindm;

import java.util.concurrent.atomic.AtomicInteger;

public class Node {
    public final String address;
    public final int name;
    private static final int MAX_ERRORS = 10;
    private final AtomicInteger errors;
    private volatile boolean isAlive;

    Node(String address, int name) {
        this.address = address;
        this.name = name;
        isAlive = true;
        errors = new AtomicInteger(0);
    }

    public boolean isAlive() {
        return isAlive;
    }

    public void captureError() {
        if (errors.incrementAndGet() > MAX_ERRORS) {
            isAlive = false;
        }
    }

}
