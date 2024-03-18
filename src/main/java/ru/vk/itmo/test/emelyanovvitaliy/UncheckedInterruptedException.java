package ru.vk.itmo.test.emelyanovvitaliy;

public class UncheckedInterruptedException extends RuntimeException {

    public UncheckedInterruptedException(InterruptedException e) {
        super(e);
    }
}
