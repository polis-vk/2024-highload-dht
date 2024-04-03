package ru.vk.itmo.test.asvistukhin;

import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.vk.itmo.ServiceConfig;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class ProxyRequestHandler {
    private static final Logger log = LoggerFactory.getLogger(ProxyRequestHandler.class);

    private final Map<String, HttpClient> clients;
    private final Map<String, Integer> urlHashes;

    public ProxyRequestHandler(ServiceConfig serviceConfig) {
        this.clients = HashMap.newHashMap(serviceConfig.clusterUrls().size() - 1);

        for (String url : serviceConfig.clusterUrls()) {
            if (!Objects.equals(url, serviceConfig.selfUrl())) {
                clients.put(url, HttpClient.newHttpClient());
            }
        }
        this.urlHashes = HashMap.newHashMap(serviceConfig.clusterUrls().size());
        for (String url : serviceConfig.clusterUrls()) {
            urlHashes.put(url, Hash.murmur3(url));
        }
    }

    public synchronized void close() {
        clients.values().forEach(HttpClient::close);
    }

    public Map<String, Response> proxyRequests(Request request, List<String> nodeUrls) throws IOException {
        Map<String, Response> responses = HashMap.newHashMap(nodeUrls.size());
        for (String url : nodeUrls) {
            Response response = proxyRequest(request, url);
            responses.put(url, response);
        }

        return responses;
    }

    public Response proxyRequest(Request request, String proxiedNodeUrl) throws IOException {
        String id = request.getParameter("id=");
        byte[] body = request.getBody();
        URI uri = URI.create(proxiedNodeUrl + request.getPath() + "?id=" + id);
        try {
            HttpResponse<byte[]> httpResponse = clients.get(proxiedNodeUrl).send(
                HttpRequest.newBuilder()
                    .uri(uri)
                    .method(
                        request.getMethodName(),
                        body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body)
                    )
                    .header(RequestWrapper.SELF_HEADER, "true")
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray());
            Response response = new Response(proxyResponseCode(httpResponse), httpResponse.body());
            long timestamp = httpResponse.headers().firstValueAsLong(RequestWrapper.NIO_TIMESTAMP_HEADER).orElse(0);
            response.addHeader(RequestWrapper.NIO_TIMESTAMP_STRING_HEADER + timestamp);
            return response;
        } catch (InterruptedException ex) {
            log.error("Proxy request thread interrupted", ex);
            Thread.currentThread().interrupt();
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (IllegalArgumentException ex) {
            log.error("IllegalArgumentException during proxy request to another node", ex);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        } catch (ConnectException ex) {
            log.error("ConnectException during proxy request to another node", ex);
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
