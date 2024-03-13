package ru.vk.itmo.test.kovalchukvladislav.dao.model;

public class MemoryOverflowException extends RuntimeException {
    public MemoryOverflowException(String message) {
        super(message);
    }
}
