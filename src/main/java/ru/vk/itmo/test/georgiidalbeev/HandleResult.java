package ru.vk.itmo.test.georgiidalbeev;

public record HandleResult(int status, byte[] data, long timestamp) {

    public HandleResult(int status, byte[] data, long timestamp) {
        this.status = status;
        this.data = data;
        this.timestamp = timestamp;
    }

    public HandleResult(int status, byte[] data) {
        this(status, data, 0);
    }
}
