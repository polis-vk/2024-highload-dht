package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.http.Request;
import one.nio.http.Response;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class Client {
    private final HttpClient client;

    private String url;

    Client() {
        client = HttpClient.newBuilder().build();
    }

    private byte[] getRequestBody(Request request) {
        return request.getBody() == null ? new byte[0] : request.getBody();
    }

    private HttpRequest getProxyRequest(Request request) {
        return HttpRequest.newBuilder(URI.create(url + request.getURI()))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(getRequestBody(request))).build();
    }

    private HttpResponse<byte[]> getProxyResponse(Request request) throws IOException,
            InterruptedException {
        return client.send(getProxyRequest(request),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    public Response handleProxyRequest(Request request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = getProxyResponse(request);
        String statusCode = switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> Response.OK;
            case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
            case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
            case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
            case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
            default -> throw new IllegalStateException("Unexpected value: " + response.statusCode());
        };
        return new Response(statusCode, response.body());
    }

    public void setUrl(String url) {
        this.url = url;
    }
}
