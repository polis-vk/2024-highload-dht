package ru.vk.itmo.test.smirnovdmitrii.dao.util.exceptions;

public class LockError extends RuntimeException {
    public LockError(final String message) {
        super(message);
    }
}
