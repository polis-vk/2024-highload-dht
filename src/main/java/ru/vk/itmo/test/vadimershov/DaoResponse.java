package ru.vk.itmo.test.vadimershov;

import one.nio.http.Response;

public class DaoResponse extends Response {

    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";

    public DaoResponse(String resultCode, byte[] body) {
        super(resultCode, body);
    }

    public static DaoResponse ok(byte[] body) {
        return new DaoResponse(OK, body);
    }

    public static DaoResponse empty(String code) {
        return new DaoResponse(code, DaoResponse.EMPTY);
    }

}
