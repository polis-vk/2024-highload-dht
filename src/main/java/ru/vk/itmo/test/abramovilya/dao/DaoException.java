package ru.vk.itmo.test.abramovilya.dao;

public class DaoException extends RuntimeException {
    public DaoException(String cause) {
        super(cause);
    }

    public static final class DaoMemoryException extends DaoException {
        public DaoMemoryException(String cause) {
            super(cause);
        }
    }
}
