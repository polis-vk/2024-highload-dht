package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Request;
import one.nio.http.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.SortedMap;
import java.util.TreeMap;

public class ProxyRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);

    private final ServiceConfig serviceConfig;
    private final ArrayList<HttpClient> nodes;
    private final SortedMap<BigInteger, Integer> hashRing = new TreeMap<>();
    private final int selfId;

    public ProxyRequestHandler(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        this.selfId = serviceConfig.clusterUrls().indexOf(serviceConfig.selfUrl());
        this.nodes = new ArrayList<>(serviceConfig.clusterUrls().size());

        try {
            for (int i = 0; i < serviceConfig.clusterUrls().size(); i++) {
                nodes.add(HttpClient.newHttpClient());
                BigInteger hash = new BigInteger(
                    1,
                    MessageDigest.getInstance("SHA-1")
                        .digest(serviceConfig.clusterUrls().get(i).getBytes(StandardCharsets.UTF_8))
                );
                hashRing.put(hash, i);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to initialize hash function", e);
        }
    }

    public synchronized void close() {
        nodes.forEach(HttpClient::close);
    }

    public Response proxyRequest(Request request) {
        String id = request.getParameter("id=");
        int nodeId = getNodeByKey(id);

        HttpRequest.BodyPublisher bodyPublisher = request.getBody() == null ?
            HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.getBody());

        URI uri = URI.create(serviceConfig.clusterUrls().get(nodeId) + request.getPath() + "?id=" + id);
        log.debug("Proxy request to {}", uri);
        try {
            HttpResponse<byte[]> response = nodes.get(nodeId).send(
                HttpRequest.newBuilder()
                    .uri(uri)
                    .method(request.getMethodName(), bodyPublisher)
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            return parseResponse(response);
        } catch (InterruptedException ex) {
            log.error("Proxy request thread interrupted", ex);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IOException ex) {
            log.error("IOException during proxy request to another node", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    public boolean isNeedProxy(String id) {
        return getNodeByKey(id) != selfId;
    }

    private Response parseResponse(HttpResponse<byte[]> response) {
        String responseHttpCode = switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            case 405 -> Response.METHOD_NOT_ALLOWED;
            case 503 -> Response.SERVICE_UNAVAILABLE;
            default -> Response.INTERNAL_ERROR;
        };

        return new Response(responseHttpCode, response.body());
    }

    private int getNodeByKey(String id) {
        try {
            BigInteger hash = new BigInteger(
                1,
                MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8))
            );
            SortedMap<BigInteger, Integer> tailMap = hashRing.tailMap(hash);
            hash = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();
            return hashRing.get(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Failed to compute hash", e);
        }
    }
}
