package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.http.Response;

public class ReplicaEmptyResponse implements NodeResponse {
    private final int statusCode;

    public ReplicaEmptyResponse(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public byte[] body() {
        return Response.EMPTY;
    }

    @Override
    public String getHeader(String key) {
        return null;
    }
}
