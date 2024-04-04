package ru.vk.itmo.test.viktorkorotkikh.http;

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
    public String getHeader(String key) {
        return response.getHeader(key + ':');
    }

    public Response getOriginal() {
        return response;
    }
}
