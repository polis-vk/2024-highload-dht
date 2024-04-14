package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Response;

public class OneNioNodeResponse implements NodeResponse {
    private final Response response;
    private final int statusCode;

    public OneNioNodeResponse(Response response) {
        this.response = response;
        this.statusCode = response.getStatus();
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public byte[] body() {
        return response.getBody();
    }

    @Override
    public Response okResponse() {
        return statusCode >= 200 && statusCode < 300 ? response : null;
    }

    @Override
    public String getHeader(String key) {
        return response.getHeader(key + ':');
    }

    public Response getOriginal() {
        return response;
    }
}
