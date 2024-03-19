package ru.vk.itmo.test.smirnovdmitrii.application.properties;

public class PropertyException extends RuntimeException {
    public PropertyException(final String message) {
        super(message);
    }

    public PropertyException(final Exception e) {
        super(e);
    }
}
