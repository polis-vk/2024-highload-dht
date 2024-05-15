package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class ProxyRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);

    private final Map<String, HttpClient> clients;
    private final Map<String, Integer> urlHashes;

    public ProxyRequestHandler(ServiceConfig serviceConfig) {
        this.clients = new HashMap<>();
        for (String url : serviceConfig.clusterUrls()) {
            if (!Objects.equals(url, serviceConfig.selfUrl())) {
                clients.put(url, HttpClient.newHttpClient());
            }
        }
        this.urlHashes = serviceConfig.clusterUrls().stream()
            .collect(Collectors.toMap(url -> url, Hash::murmur3));
    }

    public synchronized void close() {
        clients.values().forEach(HttpClient::close);
    }

    public List<CompletableFuture<Response>> proxyRequests(
        Request request,
        List<String> nodeUrls,
        int ack,
        List<Response> collectedResponses,
        AtomicInteger unsuccessfulResponsesCount
    ) {
        List<CompletableFuture<Response>> futures = new ArrayList<>();
        AtomicInteger responsesCollected = new AtomicInteger();

        for (String url : nodeUrls) {
            if (unsuccessfulResponsesCount.get() >= ack) {
                futures.add(CompletableFuture.completedFuture(null));
            }

            CompletableFuture<Response> futureResponse = proxyRequest(request, url);
            CompletableFuture<Response> resultFuture = futureResponse.thenApply(response -> {
                boolean success = ServerImpl.isSuccessProcessed(response.getStatus());
                if (success && responsesCollected.getAndIncrement() < ack) {
                    collectedResponses.add(response);
                } else if (collectedResponses.size() < ack) {
                    unsuccessfulResponsesCount.incrementAndGet();
                }
                return response;
            });
            futures.add(resultFuture);
        }
        return futures;
    }

    private CompletableFuture<Response> proxyRequest(Request request, String proxiedNodeUrl) {
        String id = request.getParameter("id=");
        byte[] body = request.getBody();
        URI uri = URI.create(proxiedNodeUrl + request.getPath() + "?id=" + id);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .method(
                request.getMethodName(),
                body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(body)
            )
            .header(RequestWrapper.SELF_HEADER, "true");

        CompletableFuture<HttpResponse<byte[]>> httpResponseFuture = clients.get(proxiedNodeUrl)
            .sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());

        return httpResponseFuture.thenApply(httpResponse -> {
            Response response = new Response(proxyResponseCode(httpResponse), httpResponse.body());
            long timestamp = Long.parseLong(
                httpResponse.headers()
                    .firstValue(RequestWrapper.NIO_TIMESTAMP_HEADER)
                    .orElse("0")
            );
            response.addHeader(RequestWrapper.NIO_TIMESTAMP_STRING_HEADER + timestamp);
            return response;
        }).exceptionally(ex -> {
            log.error("Exception during proxy request to another node", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        });
    }

    private String proxyResponseCode(HttpResponse<byte[]> response) {
        return switch (response.statusCode()) {
            case HttpURLConnection.HTTP_OK -> Response.OK;
            case HttpURLConnection.HTTP_CREATED -> Response.CREATED;
            case HttpURLConnection.HTTP_ACCEPTED -> Response.ACCEPTED;
            case HttpURLConnection.HTTP_BAD_REQUEST -> Response.BAD_REQUEST;
            case HttpURLConnection.HTTP_NOT_FOUND -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };
    }

    public List<String> getNodesByHash(int numOfNodes) {
        return urlHashes.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
            .limit(numOfNodes)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
}
