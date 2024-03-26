package ru.vk.itmo.test.timofeevkirill;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static ru.vk.itmo.test.timofeevkirill.RequestHandler.HTTP_TIMESTAMP_HEADER;
import static ru.vk.itmo.test.timofeevkirill.TimofeevServer.NIO_TIMESTAMP_HEADER;
import static ru.vk.itmo.test.timofeevkirill.TimofeevServer.PATH;

public class TimofeevProxyService {
    public static final String IS_SELF_PROCESS = "X-Self";
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

    public Map<String, Response> proxyRequests(Request request, List<String> nodeUrls, String id) throws IOException {
        Map<String, Response> responses = new HashMap<>(nodeUrls.size());
        for (String url : nodeUrls) {
            Response response = proxyRequest(request, url, id);
            responses.put(url, response);
        }

        return responses;
    }

    public Response proxyRequest(Request request, String proxiedNodeUrl, String id) throws IOException {
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
                            .header(IS_SELF_PROCESS, "true")
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            Response response = new Response(proxyResponseCode(httpResponse), httpResponse.body());
            long timestamp = httpResponse.headers().firstValueAsLong(HTTP_TIMESTAMP_HEADER).orElse(0);
            response.addHeader(NIO_TIMESTAMP_HEADER + timestamp);
            return response;
        } catch (InterruptedException e) {
            logger.error("Proxy request exception: ", e);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IllegalArgumentException e) {
            logger.error("Unsupported method: ", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private String proxyResponseCode(HttpResponse<byte[]> response) {
        return switch (response.statusCode()) {
            case 200 -> Response.OK;
            case 201 -> Response.CREATED;
            case 202 -> Response.ACCEPTED;
            case 400 -> Response.BAD_REQUEST;
            case 404 -> Response.NOT_FOUND;
            default -> Response.INTERNAL_ERROR;
        };
    }

    public List<String> getNodesByHash(String id, int numOfNodes) {
        return urlHashes.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(numOfNodes)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }
}
