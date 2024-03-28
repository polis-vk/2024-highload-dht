package ru.vk.itmo.test.smirnovandrew;

public class ValWithTime {
    private final byte[] value;
    private final long timestamp;

    ValWithTime(byte[] value, long timestamp) {
        this.value = value;
        this.timestamp = timestamp;
    }

    public byte[] value() {
        return value;
    }

    public long timestamp() {
        return timestamp;
    }
}