package ru.vk.itmo.test.andreycheshev;

public class ResponseElements implements Comparable<ResponseElements> {
    private final int status;
    private final ByteArrayWrapper body;
    private final long timestamp;

    public ResponseElements(int status, byte[] body, long timestamp) {
        this.status = status;
        this.body = new ByteArrayWrapper(body);
        this.timestamp = timestamp;
    }

    public int getStatus() {
        return status;
    }

    public byte[] getBody() {
        return body.get();
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public int compareTo(ResponseElements o) {
        long diff = timestamp - o.timestamp;
        if (diff > 0) {
            return -1;
        } else if (diff < 0) {
            return 1;
        }
        return 0;
    }
}
