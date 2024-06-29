package ru.vk.itmo.test.georgiidalbeev;

public record MyHandleResult(int status, byte[] data, long timestamp) {

    public MyHandleResult(int status, byte[] data, long timestamp) {
        this.status = status;
        this.data = data;
        this.timestamp = timestamp;
    }

    public MyHandleResult(int status, byte[] data) {
        this(status, data, 0);
    }
}
