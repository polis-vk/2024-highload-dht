package ru.vk.itmo.test.vadimershov.exceptions;

import java.io.IOException;

public class DaoException extends IOException {

    public DaoException(String message, Throwable cause) {
        super(message, cause);
    }
}
