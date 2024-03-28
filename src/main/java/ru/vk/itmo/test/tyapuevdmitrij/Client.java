package ru.vk.itmo.test.tyapuevdmitrij;

import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

public class Client {
    private static final Logger logger = LoggerFactory.getLogger(Client.class);
    private final HttpClient httpClient;

    private String url;

    private long timeStamp;

    public static final String PROXY_TIMESTAMP_HEADER = "proxy";

    public static final String NODE_TIMESTAMP_HEADER = "node";

    Client() {
        httpClient = HttpClient.newBuilder().build();
    }

    private byte[] getRequestBody(Request request) {
        return request.getBody() == null ? new byte[0] : request.getBody();
    }

    private HttpRequest getProxyRequest(Request request) {
        return HttpRequest.newBuilder(URI.create(url + request.getURI()))
                .method(request.getMethodName(),
                        HttpRequest.BodyPublishers.ofByteArray(getRequestBody(request)))
                .header(PROXY_TIMESTAMP_HEADER, String.valueOf(timeStamp))
                .build();
    }

    private HttpResponse<byte[]> getProxyResponse(Request request) {
        try {
            return httpClient.send(getProxyRequest(request),
                    HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            logger.error("can't reach target node");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("can't reach target node");
            return null;
        }
    }

    public Response handleProxyRequest(Request request) {
        HttpResponse<byte[]> response = getProxyResponse(request);
        if (response == null) {
            return null;
        }
        String statusCode = switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> Response.OK;
            case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
            case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
            case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
            case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
            default -> throw new IllegalStateException("Unexpected value: " + response.statusCode());
        };
        Optional<String> timeStamp = response.headers().firstValue(NODE_TIMESTAMP_HEADER);
        Response finalResponse = new Response(statusCode, response.body());
        if (timeStamp.isPresent()) {
            long time = Long.parseLong(timeStamp.get());
            finalResponse.addHeader(Client.NODE_TIMESTAMP_HEADER + ":" + time);
        }
        return finalResponse;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }
}
