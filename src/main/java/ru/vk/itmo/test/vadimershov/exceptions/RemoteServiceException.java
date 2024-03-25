package ru.vk.itmo.test.vadimershov.exceptions;

import java.io.IOException;

public class RemoteServiceException extends IOException {
    private final String httpCode;
    private final String url;

    public RemoteServiceException(String httpCode, String url) {
        this.httpCode = httpCode;
        this.url = url;
    }

    public String getHttpCode() {
        return this.httpCode;
    }

    public String getUrl() {
        return url;
    }
}
