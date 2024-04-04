package ru.vk.itmo.test.viktorkorotkikh.http;

public interface NodeResponse {
    int statusCode();

    byte[] body();

    String getHeader(String key);
}
