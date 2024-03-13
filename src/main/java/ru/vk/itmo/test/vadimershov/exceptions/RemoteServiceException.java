package ru.vk.itmo.test.vadimershov.exceptions;


public class RemoteServiceException extends RuntimeException {
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
