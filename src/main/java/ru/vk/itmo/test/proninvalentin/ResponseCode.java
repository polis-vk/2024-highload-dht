package ru.vk.itmo.test.proninvalentin;

import one.nio.http.Response;

public class ResponseCode extends Response {
    public static final String TOO_MANY_REQUESTS = "429 Too Many Requests";

    public ResponseCode(String resultCode) {
        super(resultCode);
    }

    public ResponseCode(String resultCode, byte[] body) {
        super(resultCode, body);
    }

    public ResponseCode(Response prototype) {
        super(prototype);
    }
}
