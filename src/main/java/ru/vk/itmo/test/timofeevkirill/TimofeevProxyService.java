package ru.vk.itmo.test.timofeevkirill;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static ru.vk.itmo.test.timofeevkirill.TimofeevServer.PATH;

public class TimofeevProxyService {
    private static final Logger logger = LoggerFactory.getLogger(TimofeevProxyService.class);
    private static final HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
    private final Map<String, HttpClient> httpClients;
    private final Map<String, Integer> urlHashes;

    public TimofeevProxyService(ServiceConfig serviceConfig) {
        this.httpClients = new HashMap<>(serviceConfig.clusterUrls().size() - 1);
        for (String url : serviceConfig.clusterUrls()) {
            if (!Objects.equals(url, serviceConfig.selfUrl())) {
                httpClients.put(url, HttpClient.newHttpClient());
            }
        }
        this.urlHashes = new HashMap<>(serviceConfig.clusterUrls().size());
        for (String url : serviceConfig.clusterUrls()) {
            urlHashes.put(url, Hash.murmur3(url));
        }
    }

    public void close() {
        for (HttpClient httpClient : httpClients.values()) {
            httpClient.close();
        }
    }

    public Map<String, Response> proxyAsyncRequests(Request request, List<String> nodeUrls, String id)
            throws InterruptedException, ExecutionException {
        Map<String, CompletableFuture<Response>> futures = new HashMap<>(nodeUrls.size());

        for (String url : nodeUrls) {
            CompletableFuture<Response> future = proxyRequestAsync(request, url, id);
            futures.put(url, future);
        }

        CompletableFuture<Void> allFutures =
                CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[nodeUrls.size()]));
        allFutures.get();

        Map<String, Response> responses = new HashMap<>();
        for (Map.Entry<String, CompletableFuture<Response>> entry : futures.entrySet()) {
            String url = entry.getKey();
            CompletableFuture<Response> future = entry.getValue();
            responses.put(url, future.join());
        }

        return responses;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private CompletableFuture<Response> proxyRequestAsync(Request request, String proxiedNodeUrl, String id) {
        byte[] body = request.getBody();
        URI uri = URI.create(proxiedNodeUrl + PATH + "?id=" + id);

        CompletableFuture<Response> future = new CompletableFuture<>();

        httpClients.get(proxiedNodeUrl).sendAsync(
                        HttpRequest.newBuilder()
                                .uri(uri)
                                .method(
                                        request.getMethodName(),
                                        body == null
                                                ? HttpRequest.BodyPublishers.noBody()
                                                : HttpRequest.BodyPublishers.ofByteArray(body)
                                )
                                .header(RequestData.SELF_PROCESS_HEADER, "")
                                .build(),
                        HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(httpResponse -> {
                    Response response = new Response(proxyResponseCode(httpResponse), httpResponse.body());
                    long timestamp =
                            httpResponse.headers().firstValueAsLong(RequestData.NIO_TIMESTAMP_HEADER).orElse(0);
                    response.addHeader(RequestData.NIO_TIMESTAMP_STRING_HEADER + timestamp);
                    return response;
                })
                .exceptionally(ex -> {
                    logger.error("Proxy request exception: ", ex);
                    return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
                })
                .thenAccept(future::complete);

        return future;
    }

    /**
     * @deprecated use async method to wait responses parallel instead {@link #proxyAsyncRequests(Request, List, String)}.
     */
    @Deprecated
    public Map<String, Response> proxyRequests(Request request, List<String> nodeUrls, String id) throws IOException {
        Map<String, Response> responses = new HashMap<>(nodeUrls.size());
        for (String url : nodeUrls) {
            Response response = proxyRequest(request, url, id);
            responses.put(url, response);
        }

        return responses;
    }

    /**
     * @deprecated use to support {@link #proxyRequests(Request, List, String)}.
     */
    @Deprecated
    private Response proxyRequest(Request request, String proxiedNodeUrl, String id) throws IOException {
        byte[] body = request.getBody();
        URI uri = URI.create(proxiedNodeUrl + PATH + "?id=" + id);
        try {
            HttpResponse<byte[]> httpResponse = httpClients.get(proxiedNodeUrl).send(
                    requestBuilder
                            .uri(uri)
                            .method(
                                    request.getMethodName(),
                                    body == null
                                            ? HttpRequest.BodyPublishers.noBody()
                                            : HttpRequest.BodyPublishers.ofByteArray(body)
                            )
                            .header(RequestData.SELF_PROCESS_HEADER, "")
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Response response = new Response(proxyResponseCode(httpResponse), httpResponse.body());
            long timestamp = httpResponse.headers().firstValueAsLong(RequestData.NIO_TIMESTAMP_HEADER).orElse(0);
            response.addHeader(RequestData.NIO_TIMESTAMP_STRING_HEADER + timestamp);
            return response;
        } catch (InterruptedException e) {
            logger.error("Proxy request exception: ", e);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IllegalArgumentException e) {
            logger.error("Unsupported method: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (ConnectException e) {
            logger.error("Connection exception: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
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
