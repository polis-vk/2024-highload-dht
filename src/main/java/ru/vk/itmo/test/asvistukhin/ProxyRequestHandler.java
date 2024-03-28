package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.SortedMap;
import java.util.TreeMap;

public class ProxyRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);

    private final HashMap<String, HttpClient> nodes;
    private final SortedMap<Integer, String> hashRing = new TreeMap<>();
    private final String selfUrl;

    public ProxyRequestHandler(ServiceConfig serviceConfig) {
        this.nodes = new HashMap<>(serviceConfig.clusterUrls().size());
        this.selfUrl = serviceConfig.selfUrl();

        for (int i = 0; i < serviceConfig.clusterUrls().size(); i++) {
            String url = serviceConfig.clusterUrls().get(i);
            nodes.put(url, HttpClient.newHttpClient());
            int hash = Hash.murmur3(url);
            hashRing.put(hash, url);
        }
    }

    public synchronized void close() {
        nodes.values().forEach(HttpClient::close);
    }

    public Response proxyRequest(Request request) {
        String id = request.getParameter("id=");
        String nodeUrl = getNodeByKey(id);

        HttpRequest.BodyPublisher bodyPublisher = request.getBody() == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(request.getBody());

        URI uri = URI.create(nodeUrl + request.getPath() + "?id=" + id);
        log.debug("Proxy request to {}", uri);
        try {
            HttpResponse<byte[]> response = nodes.get(nodeUrl).send(
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
        return !getNodeByKey(id).equals(selfUrl);
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

    private String getNodeByKey(String id) {
        int hash = Hash.murmur3(id);
        SortedMap<Integer, String> tailMap = hashRing.tailMap(hash);
        hash = tailMap.isEmpty() ? hashRing.firstKey() : tailMap.firstKey();
        return hashRing.get(hash);
    }
}
