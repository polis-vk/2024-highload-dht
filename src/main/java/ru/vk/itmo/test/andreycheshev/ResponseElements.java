package ru.vk.itmo.test.andreycheshev;

import java.util.Comparator;

public class ResponseElements implements Comparator<ResponseElements> {
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
    public int compare(ResponseElements o1, ResponseElements o2) {
        long timestamp1 = o1.getTimestamp();
        long timestamp2 = o2.getTimestamp();
        if (timestamp1 > timestamp2) {
            return -1;
        } else if (timestamp1 < timestamp2) {
            return 1;
        }
        return 0;
    }
}
