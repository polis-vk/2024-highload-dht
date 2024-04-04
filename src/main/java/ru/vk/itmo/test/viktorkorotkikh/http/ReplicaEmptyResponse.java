package ru.vk.itmo.test.viktorkorotkikh.http;

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
        return new byte[0];
    }

    @Override
    public String getHeader(String key) {
        return null;
    }
}
