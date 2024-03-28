package ru.vk.itmo.test.viktorkorotkikh.http;

import ru.vk.itmo.test.viktorkorotkikh.util.AcceptAllBiPredicate;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;

public class ReplicaEmptyResponse implements HttpResponse<byte[]> {
    private final int statusCode;

    public ReplicaEmptyResponse(int statusCode) {
        this.statusCode = statusCode;
    }

    @Override
    public int statusCode() {
        return statusCode;
    }

    @Override
    public HttpRequest request() {
        return null;
    }

    @Override
    public Optional<HttpResponse<byte[]>> previousResponse() {
        return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
        return HttpHeaders.of(Map.of(), AcceptAllBiPredicate.INSTANCE);
    }

    @Override
    public byte[] body() {
        return new byte[0];
    }

    @Override
    public Optional<SSLSession> sslSession() {
        return Optional.empty();
    }

    @Override
    public URI uri() {
        return null;
    }

    @Override
    public HttpClient.Version version() {
        return null;
    }
}
