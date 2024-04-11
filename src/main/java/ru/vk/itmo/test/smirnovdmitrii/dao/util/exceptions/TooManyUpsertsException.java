package ru.vk.itmo.test.smirnovdmitrii.dao.util.exceptions;

public class TooManyUpsertsException extends RuntimeException {
    public TooManyUpsertsException(final Throwable throwable) {
        super(throwable);
    }

    public TooManyUpsertsException(final String message) {
        super(message);
    }
}
