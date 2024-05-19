package ru.vk.itmo.test.vadimershov;

import one.nio.http.Response;

public class DaoResponse extends Response {

    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";
    public static final String NOT_ENOUGH_REPLICAS = "504 Not Enough Replicas";

    public static final String X_TIMESTAMP = "X-timestamp: ";

    public DaoResponse(String resultCode, byte[] body) {
        super(resultCode, body);
    }

    public DaoResponse(String resultCode, byte[] body, long timestamp) {
        super(resultCode, body);
        this.addHeader(X_TIMESTAMP + timestamp);
    }

    public static DaoResponse ok(byte[] body) {
        return new DaoResponse(OK, body);
    }

    public static DaoResponse ok(byte[] body, long timestamp) {
        return new DaoResponse(OK, body, timestamp);
    }

    public static DaoResponse empty(String code) {
        return new DaoResponse(code, DaoResponse.EMPTY);
    }

    public static DaoResponse empty(String code, long timestamp) {
        return new DaoResponse(code, DaoResponse.EMPTY, timestamp);
    }

}
