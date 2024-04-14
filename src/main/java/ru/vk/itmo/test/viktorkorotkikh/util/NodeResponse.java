package ru.vk.itmo.test.viktorkorotkikh.util;

import one.nio.http.Response;

public interface NodeResponse {
    int statusCode();

    byte[] body();

    Response okResponse();

    String getHeader(String key);
}
