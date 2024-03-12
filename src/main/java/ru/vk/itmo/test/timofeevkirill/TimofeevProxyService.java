package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;

import static ru.vk.itmo.test.timofeevkirill.TimofeevServer.PATH;

public class TimofeevProxyService {
    private static final Logger logger = LoggerFactory.getLogger(TimofeevProxyService.class);
    private static final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
    private final ArrayList<HttpClient> httpClients;
    private final ServiceConfig serviceConfig;
    private final int selfId;

    public TimofeevProxyService(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.selfId = serviceConfig.clusterUrls().indexOf(serviceConfig.selfUrl());
        this.httpClients = new ArrayList<>(serviceConfig.clusterUrls().size() - 1);
        for (int i = 0; i < serviceConfig.clusterUrls().size(); i++) {
            if (i != selfId) {
                httpClients.add(HttpClient.newHttpClient());
            }
        }
    }

    public void close() {
        for (HttpClient httpClient : httpClients) {
            httpClient.close();
        }
    }

    public Response proxyRequest(int method, String id, byte[] body) throws IOException {
        int proxiedNodeId = getNodeIdByHash(id);
        int clientId = proxiedNodeId > selfId ? proxiedNodeId - 1 : proxiedNodeId;
        URI uri = URI.create(serviceConfig.clusterUrls().get(proxiedNodeId) + PATH + "?id=" + id);
        try {
            HttpResponse<byte[]> response = httpClients.get(clientId).send(
                    requestBuilder
                            .uri(uri)
                            .method(
                                    requestMethodToString(method),
                                    body == null ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(body)
                            )
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return proxyResponse(response, response.body());
        } catch (InterruptedException e) {
            logger.error("Proxy request exception: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IllegalArgumentException e) {
            logger.error("Unsupported method: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response proxyResponse(HttpResponse<byte[]> response, byte[] body) {
        String responseCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };

        return new Response(responseCode, body);
    }

    private String requestMethodToString(int method) {
        return switch (method) {
            case Request.METHOD_GET -> "GET";
            case Request.METHOD_PUT -> "PUT";
            case Request.METHOD_DELETE -> "DELETE";
            default -> throw new IllegalArgumentException();
        };
    }

    public boolean needProxyRequest(String id) {
        return getNodeIdByHash(id) != selfId;
    }

    private int getNodeIdByHash(String id) {
        int maxValue = Integer.MIN_VALUE;
        int nodeId = 0;
        for (int i = 0; i < serviceConfig.clusterUrls().size(); i++) {
            String clusterUrl = serviceConfig.clusterUrls().get(i);
            int hash = (id + clusterUrl).hashCode();

            if (hash > maxValue) {
                maxValue = hash;
                nodeId = i;
            }
        }

        return nodeId;
    }
}
