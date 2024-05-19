package ru.vk.itmo.test.tuzikovalexandr;

import one.nio.http.Response;

public class ResponseWithUrl {
    private final String url;
    private final Response response;

    public ResponseWithUrl(String url, Response response) {
        this.url = url;
        this.response = response;
    }

    public Response getResponse() {
        return response;
    }
}
