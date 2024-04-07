package ru.vk.itmo.test.andreycheshev;

public class ResponseElements implements Comparable<ResponseElements> {
    private final int status;
    private final byte[] body;
    private final long timestamp;

    public ResponseElements(int status, byte[] body, long timestamp) {
        this.status = status;
        this.body = body;
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public byte[] getBody() {
        return body;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(ResponseElements o) {
        if (this.getTimestamp() > o.getTimestamp()) {
            return -1;
        } else if (this.getTimestamp() < o.getTimestamp()) {
            return 1;
        }
        return 0;
    }
}
