package ru.vk.itmo.test.khadyrovalmasgali.replication;

import one.nio.http.Response;

public class HandleResult {

    private final int status;
    private final byte[] data;
    private final long timestamp;

    public HandleResult(int status, byte[] data, long timestamp) {
        this.status = status;
        this.data = data;
        this.timestamp = timestamp;
    }

    public HandleResult(int status, byte[] data) {
        this(status, data, 0);
    }

    public HandleResult(Response response) {
        this(response.getStatus(), response.getBody(), 0);
    }

    public int status() {
        return status;
    }

    public byte[] data() {
        return data;
    }

    public long timestamp() {
        return timestamp;
    }
}
