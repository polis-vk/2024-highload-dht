package ru.vk.itmo.test.vadimershov.exceptions;

import ru.vk.itmo.test.vadimershov.DaoResponse;

public class FailedSharding extends RuntimeException {

    private final String httpCode;

    public FailedSharding(String httpCode) {
        this.httpCode = httpCode;
    }

    public FailedSharding() {
        this.httpCode = DaoResponse.NOT_ENOUGH_REPLICAS;
    }

    public String getHttpCode() {
        return httpCode;
    }
}
