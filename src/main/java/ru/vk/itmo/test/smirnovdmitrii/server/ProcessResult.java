package ru.vk.itmo.test.smirnovdmitrii.server;

public class ProcessResult {
    private final int status;
    private final byte[] data;
    private final long timestamp;

    public ProcessResult(int status, byte[] data, long timestamp) {
        this.status = status;
        this.data = data;
        this.timestamp = timestamp;
    }

    public ProcessResult(int status, byte[] data) {
        this(status, data, -1);
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
