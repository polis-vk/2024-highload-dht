package ru.vk.itmo.test.reshetnikovaleksei;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import ru.vk.itmo.ServiceConfig;

public class RequestRouter implements Closeable {
    private final List<String> nodes;
    private final HttpClient httpClient;

    public RequestRouter(ServiceConfig config) {
        this.nodes = config.clusterUrls();
        this.httpClient = HttpClient.newHttpClient();
    }

    public Response redirect(String node, Request request) throws IOException, InterruptedException {
        String url = node + request.getURI();
        HttpRequest redirectRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(
                                request.getBody() == null ? new byte[0] : request.getBody()))
                .build();

        HttpResponse<byte[]> httpResponse = httpClient.send(redirectRequest, HttpResponse.BodyHandlers.ofByteArray());
        return convertResponse(httpResponse);
    }

    public String getNode(String id) {
        int maxHash = Integer.MIN_VALUE;
        String maxHashNode = null;

        for (String node : nodes) {
            int hash = Hash.murmur3(id + node);
            if (hash > maxHash) {
                maxHash = hash;
                maxHashNode = node;
            }
        }

        return maxHashNode;
    }

    @Override
    public void close() {
        httpClient.close();
    }

    private Response convertResponse(HttpResponse<byte[]> response) {
        String resultCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            default -> Response.INTERNAL_ERROR;
        };

        return new Response(resultCode, response.body());
    }
}
