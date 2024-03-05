package ru.vk.itmo.test.kislovdanil.dao.exceptions;

public class DBException extends RuntimeException {
    public DBException(Exception e) {
        super(e);
    }

    public DBException() {
        super();
    }
}
