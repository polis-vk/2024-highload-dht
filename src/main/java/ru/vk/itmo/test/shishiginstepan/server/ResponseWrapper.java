package ru.vk.itmo.test.shishiginstepan.server;

public record ResponseWrapper(int status, byte[] data, long timestamp) {

    public ResponseWrapper(int status, byte[] data) {
        this(status, data, 0);
    }
}
