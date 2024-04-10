package ru.vk.itmo.test.kachmareugene;

import one.nio.http.Request;
import one.nio.http.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

public class JavaRequestConverter {
    private static final Map<Integer, String> intToStringCode =
            Map.of(
            202, Response.ACCEPTED,
            201, Response.CREATED,
            200, Response.OK,
            404, Response.NOT_FOUND,
            400, Response.BAD_REQUEST);
    public static HttpRequest convertRequest(String url, Request oneNIORequest, long timestamp) {
        final var body = oneNIORequest.getBody() != null
                ? HttpRequest.BodyPublishers.ofByteArray(oneNIORequest.getBody())
                : HttpRequest.BodyPublishers.noBody();

        return HttpRequest.newBuilder()
                .uri(URI.create(url + oneNIORequest.getURI()))
                .method(oneNIORequest.getMethodName(), body)
                .header(Utils.TIMESTAMP_HEADER, String.valueOf(timestamp))
                .version(HttpClient.Version.HTTP_1_1)
                .expectContinue(false)
                .build();
    }

    public static Response convertResponse(HttpResponse<byte[]> response) {
        if (response == null) {
            return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
        }

        Response oneNioResp =
                new Response(
                        intToStringCode.getOrDefault(response.statusCode(), Response.SERVICE_UNAVAILABLE),
                        response.body());

        if (!response.headers().map().containsKey(Utils.TIMESTAMP_HEADER)) {
            return oneNioResp;
        }

        oneNioResp.addHeader(
                Utils.TIMESTAMP_ONE_NIO_HEADER +
                        Long.parseLong(
                                response.headers().map().get(Utils.TIMESTAMP_HEADER).getFirst()));

        return oneNioResp;
    }
}
