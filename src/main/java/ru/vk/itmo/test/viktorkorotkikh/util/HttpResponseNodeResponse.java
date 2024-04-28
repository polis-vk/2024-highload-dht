package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Response;

import java.net.http.HttpResponse;
import java.util.List;

public class HttpResponseNodeResponse implements NodeResponse {
    private final HttpResponse<byte[]> httpResponse;

    public HttpResponseNodeResponse(HttpResponse<byte[]> httpResponse) {
        this.httpResponse = httpResponse;
    }

    @Override
    public int statusCode() {
        return httpResponse.statusCode();
    }

    @Override
    public byte[] body() {
        return httpResponse.body();
    }

    @Override
    public Response okResponse() {
        return statusCode() >= 200 && statusCode() < 300 ? Response.ok(body()) : null;
    }

    @Override
    public String getHeader(String key) {
        List<String> headerValues = httpResponse.headers().allValues(key);
        if (headerValues.isEmpty()) {
            return null;
        }
        return headerValues.getFirst();
    }
}
