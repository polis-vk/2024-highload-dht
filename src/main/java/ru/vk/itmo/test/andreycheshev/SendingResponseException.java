package ru.vk.itmo.test.andreycheshev;

import java.io.UncheckedIOException;

public class SendingResponseException extends RuntimeException {
    public SendingResponseException(Throwable cause) {
        super(cause);
    }
}
