package ru.vk.itmo.test.viktorkorotkikh.http;

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
    public String getHeader(String key) {
        List<String> headerValues = httpResponse.headers().allValues(key);
        if (headerValues.isEmpty()) {
            return null;
        }
        return headerValues.getFirst();
    }
}
