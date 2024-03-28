package ru.vk.itmo.test.viktorkorotkikh.http;

import one.nio.http.Response;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OneNioHttpResponseWrapper implements HttpResponse<byte[]> {
    private static boolean acceptAll(String x, String y) {
        return true;
    }

    private final Response response;

    public OneNioHttpResponseWrapper(Response response) {
        this.response = response;
    }

    public Response getOriginal() {
        return response;
    }

    @Override
    public int statusCode() {
        return response.getStatus();
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
        Map<String, List<String>> headers = new HashMap<>();
        for (int i = 0; i < response.getHeaderCount(); i++) {
            String header = response.getHeaders()[i];
            int delimiter = header.indexOf(":");
            if (delimiter < 0) {
                headers.put(header, List.of(""));
            } else {
                headers.put(header.substring(0, delimiter), List.of(header.substring(delimiter + 1).replaceFirst(" *", "").split(", ?")));
            }
        }
        return HttpHeaders.of(headers, OneNioHttpResponseWrapper::acceptAll);
    }

    @Override
    public byte[] body() {
        return response.getBody();
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
