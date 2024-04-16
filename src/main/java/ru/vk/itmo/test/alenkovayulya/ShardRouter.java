package ru.vk.itmo.test.alenkovayulya;

import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static ru.vk.itmo.test.alenkovayulya.ServerImpl.PATH;

public final class ShardRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShardRouter.class);
    private static final HttpClient client = HttpClient.newHttpClient();

    private ShardRouter() {
    }

    public static Response redirectRequest(String method, String id, String ownerShardUrl, byte[] body) {
        String path = ownerShardUrl + PATH + "?id=" + id;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(path))
                .method(method, HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return new Response(getHttpResponseByCode(response.statusCode()), response.body());
        } catch (Exception e) {
            LOGGER.error("Error during sending request by router", e);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private static String getHttpResponseByCode(int code) {
        return switch (code) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            default -> Response.INTERNAL_ERROR;
        };
    }
}
