package ru.vk.itmo.test.trofimovmaxim;

public record HandleResult(int status, byte[] data, long timestamp) {

    public HandleResult(int status, byte[] data) {
        this(status, data, 0);
    }
}
