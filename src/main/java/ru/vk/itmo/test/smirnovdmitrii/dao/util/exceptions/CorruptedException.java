package ru.vk.itmo.test.smirnovdmitrii.dao.util.exceptions;

public class CorruptedException extends RuntimeException {
    public CorruptedException(final String message) {
        super(message);
    }
}
